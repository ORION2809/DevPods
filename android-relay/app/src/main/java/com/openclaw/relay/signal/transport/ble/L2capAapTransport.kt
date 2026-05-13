package com.openclaw.relay.signal.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "L2capAapTransport"
private const val AAP_SERVICE_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"
private const val RECONNECT_DELAY_MS = 5000L

enum class L2capTransportState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

/**
 * L2CAP transport for Apple AAP/AACP protocol.
 *
 * Attempts to create an L2CAP Connection-Oriented Channel (CoC) socket
 * using hidden Android APIs (API 29+), with graceful fallback to RFCOMM.
 *
 * Derived from CAPod [L2capSocketFactory] concepts but adapted for DevPods'
 * coroutine/Flow architecture.
 */
class L2capAapTransport(
    private val context: Context,
    private val deviceAddress: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(L2capTransportState.IDLE)
    val state: StateFlow<L2capTransportState> = _state.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    private val _frames = Channel<ByteArray>(Channel.BUFFERED)
    val frames: Flow<ByteArray> = _frames.receiveAsFlow()

    private val disposed = AtomicBoolean(false)
    private var socket: BluetoothSocket? = null
    private var readJob: Job? = null
    private var reconnectJob: Job? = null

    fun start() {
        if (disposed.get()) return
        scope.launch { connect() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connect() {
        if (_state.value == L2capTransportState.CONNECTED || _state.value == L2capTransportState.CONNECTING) return

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: run {
            _errors.value = "Bluetooth adapter unavailable"
            _state.value = L2capTransportState.FAILED
            return
        }
        if (!adapter.isEnabled) {
            _errors.value = "Bluetooth disabled"
            scheduleReconnect()
            return
        }

        _state.value = L2capTransportState.CONNECTING
        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            _errors.value = "Invalid address"
            _state.value = L2capTransportState.FAILED
            return
        }

        adapter.cancelDiscovery()

        val newSocket = createL2capSocket(device) ?: createRfcommSocket(device)
        if (newSocket == null) {
            _errors.value = "Could not create L2CAP or RFCOMM socket"
            onConnectionFailed()
            return
        }

        try {
            newSocket.connect()
        } catch (e: IOException) {
            _errors.value = "Connect failed: ${e.message}"
            try { newSocket.close() } catch (_: IOException) {}
            onConnectionFailed()
            return
        } catch (e: SecurityException) {
            _errors.value = "Permission denied: ${e.message}"
            try { newSocket.close() } catch (_: IOException) {}
            _state.value = L2capTransportState.FAILED
            return
        }

        socket = newSocket
        _state.value = L2capTransportState.CONNECTED
        _errors.value = null
        Log.i(TAG, "AAP transport connected to $deviceAddress")

        startReadLoop()
    }

    @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
    private fun createL2capSocket(device: BluetoothDevice): BluetoothSocket? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val method = device.javaClass.getMethod(
                "createL2capCocSocket",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            method.invoke(device, 0x0100, -1) as BluetoothSocket
        } catch (e: NoSuchMethodException) {
            null
        } catch (e: IllegalAccessException) {
            null
        } catch (e: InvocationTargetException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRfcommSocket(device: BluetoothDevice): BluetoothSocket? {
        return try {
            device.createRfcommSocketToServiceRecord(UUID.fromString(AAP_SERVICE_UUID))
        } catch (e: IOException) {
            null
        }
    }

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ByteArray(1024)
            val sock = socket ?: return@launch
            try {
                val input = sock.inputStream
                while (isActive && _state.value == L2capTransportState.CONNECTED) {
                    val nRead = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        if (isActive) _errors.value = "Read error: ${e.message}"
                        break
                    }
                    if (nRead == -1) break
                    if (nRead > 0) {
                        _frames.trySend(buffer.copyOf(nRead))
                    }
                }
            } finally {
                onDisconnected()
            }
        }
    }

    fun send(frame: ByteArray): Boolean {
        if (_state.value != L2capTransportState.CONNECTED) return false
        return try {
            socket?.outputStream?.write(frame)
            socket?.outputStream?.flush()
            true
        } catch (e: IOException) {
            _errors.value = "Write error: ${e.message}"
            onDisconnected()
            false
        }
    }

    fun disconnect() {
        readJob?.cancel()
        readJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        _state.value = L2capTransportState.DISCONNECTED
    }

    fun dispose() {
        if (disposed.getAndSet(true)) return
        disconnect()
        _frames.close()
        scope.cancel()
    }

    private fun onConnectionFailed() {
        socket = null
        scheduleReconnect()
    }

    private fun onDisconnected() {
        if (_state.value == L2capTransportState.CONNECTED) {
            _state.value = L2capTransportState.DISCONNECTED
        }
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        if (!disposed.get()) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!disposed.get() && _state.value != L2capTransportState.CONNECTED) {
                connect()
            }
        }
    }
}
