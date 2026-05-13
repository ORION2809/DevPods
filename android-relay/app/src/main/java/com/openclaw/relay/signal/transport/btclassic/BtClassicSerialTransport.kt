package com.openclaw.relay.signal.transport.btclassic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BtClassicSerialTransport"
private const val DEFAULT_BUFFER_SIZE = 1024
private const val DEFAULT_RECONNECT_DELAY_MS = 3000L
private const val DEFAULT_CONNECT_TIMEOUT_MS = 15000L

enum class TransportState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    RECONNECTING,
    FAILED,
}

data class TransportError(
    val type: ErrorType,
    val message: String,
    val recoverable: Boolean = true,
) {
    enum class ErrorType {
        SOCKET_CREATE,
        CONNECTION_REFUSED,
        CONNECTION_TIMEOUT,
        SECURITY_EXCEPTION,
        IO_ERROR,
        DISCONNECTED,
    }
}

/**
 * Kotlin coroutines/Flow-based Bluetooth Classic RFCOMM transport.
 *
 * Modeled after Gadgetbridge [BtBRQueue] but adapted for DevPods' coroutine style:
 * - One read loop (suspending, not HandlerThread)
 * - One write channel (suspending send, not Handler message queue)
 * - Reconnection with exponential backoff
 * - Deterministic lifecycle (start/connect/dispose)
 */
class BtClassicSerialTransport(
    private val context: Context,
    private val deviceAddress: String,
    private val serviceUuid: UUID,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val reconnectDelayMs: Long = DEFAULT_RECONNECT_DELAY_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(TransportState.IDLE)
    val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _errors = MutableStateFlow<TransportError?>(null)
    val errors: StateFlow<TransportError?> = _errors.asStateFlow()

    private val _receivedPackets = Channel<ByteArray>(Channel.BUFFERED)
    val receivedPackets: Flow<ByteArray> = _receivedPackets.receiveAsFlow()

    private val _writeChannel = Channel<ByteArray>(Channel.BUFFERED)

    private val disposed = AtomicBoolean(false)
    private var socket: BluetoothSocket? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var reconnectJob: Job? = null

    private var consecutiveFailures = 0
    private val maxConsecutiveFailures = 5

    fun start() {
        if (disposed.get()) {
            Log.w(TAG, "Cannot start a disposed transport")
            return
        }
        scope.launch { connect() }
        startWriteLoop()
    }

    @SuppressLint("MissingPermission")
    private suspend fun connect() {
        if (_state.value == TransportState.CONNECTED || _state.value == TransportState.CONNECTING) return

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: run {
            emitError(TransportError(TransportError.ErrorType.SOCKET_CREATE, "Bluetooth adapter unavailable", false))
            return
        }
        if (!adapter.isEnabled) {
            emitError(TransportError(TransportError.ErrorType.SOCKET_CREATE, "Bluetooth is disabled", true))
            scheduleReconnect()
            return
        }

        _state.value = TransportState.CONNECTING
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            emitError(TransportError(TransportError.ErrorType.SOCKET_CREATE, "Invalid device address: $deviceAddress", false))
            _state.value = TransportState.FAILED
            return
        }

        adapter.cancelDiscovery()

        val newSocket = try {
            device.createRfcommSocketToServiceRecord(serviceUuid)
        } catch (e: IOException) {
            emitError(TransportError(TransportError.ErrorType.SOCKET_CREATE, "Socket creation failed: ${e.message}", true))
            onConnectionFailed()
            return
        }

        try {
            newSocket.connect()
        } catch (e: IOException) {
            emitError(TransportError(TransportError.ErrorType.CONNECTION_REFUSED, "Connect failed: ${e.message}", true))
            try { newSocket.close() } catch (_: IOException) {}
            onConnectionFailed()
            return
        } catch (e: SecurityException) {
            emitError(TransportError(TransportError.ErrorType.SECURITY_EXCEPTION, "Missing Bluetooth permission: ${e.message}", false))
            try { newSocket.close() } catch (_: IOException) {}
            _state.value = TransportState.FAILED
            return
        }

        socket = newSocket
        consecutiveFailures = 0
        _state.value = TransportState.CONNECTED
        _errors.value = null
        Log.i(TAG, "Connected to $deviceAddress")

        startReadLoop()
    }

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            val sock = socket ?: return@launch
            try {
                val input = sock.inputStream
                while (isActive && _state.value == TransportState.CONNECTED) {
                    val nRead = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        if (isActive) {
                            emitError(TransportError(TransportError.ErrorType.IO_ERROR, "Read error: ${e.message}", true))
                        }
                        break
                    }
                    if (nRead == -1) {
                        emitError(TransportError(TransportError.ErrorType.DISCONNECTED, "End of stream", true))
                        break
                    }
                    if (nRead > 0) {
                        val packet = buffer.copyOf(nRead)
                        _receivedPackets.trySend(packet)
                    }
                }
            } finally {
                onDisconnected()
            }
        }
    }

    private fun startWriteLoop() {
        writeJob = scope.launch {
            for (packet in _writeChannel) {
                if (_state.value != TransportState.CONNECTED) {
                    Log.w(TAG, "Dropping write: not connected")
                    continue
                }
                val sock = socket ?: continue
                try {
                    sock.outputStream.write(packet)
                    sock.outputStream.flush()
                } catch (e: IOException) {
                    emitError(TransportError(TransportError.ErrorType.IO_ERROR, "Write error: ${e.message}", true))
                    onDisconnected()
                }
            }
        }
    }

    fun send(packet: ByteArray): Boolean {
        return _writeChannel.trySend(packet).isSuccess
    }

    fun disconnect() {
        readJob?.cancel()
        readJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        _state.value = TransportState.DISCONNECTED
    }

    fun dispose() {
        if (disposed.getAndSet(true)) return
        disconnect()
        _writeChannel.close()
        scope.cancel()
    }

    private fun onConnectionFailed() {
        socket = null
        consecutiveFailures++
        if (consecutiveFailures >= maxConsecutiveFailures) {
            _state.value = TransportState.FAILED
            emitError(TransportError(TransportError.ErrorType.CONNECTION_REFUSED, "Max reconnect attempts reached", false))
            return
        }
        scheduleReconnect()
    }

    private fun onDisconnected() {
        if (_state.value == TransportState.CONNECTED) {
            _state.value = TransportState.DISCONNECTED
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
            _state.value = TransportState.RECONNECTING
            val backoff = (reconnectDelayMs * consecutiveFailures).coerceAtMost(30000L)
            delay(backoff)
            if (!disposed.get() && _state.value != TransportState.CONNECTED) {
                connect()
            }
        }
    }

    private fun emitError(error: TransportError) {
        _errors.value = error
        Log.w(TAG, "Transport error: ${error.type} — ${error.message}")
    }
}
