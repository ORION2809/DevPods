package com.openclaw.relay.signal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException
import java.util.UUID

private const val TAG = "AacpConnectionManager"

/**
 * Best-effort L2CAP/AACP connection manager for AirPods stem-press events.
 *
 * AACP (Apple Accessory Communication Protocol) runs over an L2CAP CoC
 * (Connection-oriented Channel) socket. This manager attempts to open the
 * socket, parse the basic AACP frame structure, and emit [EarbudSignalEvent]
 * values for stem presses.
 *
 * Because full AACP encryption and key exchange is not implemented here,
 * this is a best-effort layer: if the device is already bonded and the
 * system allows the L2CAP socket, stem presses are observed. If not,
 * the provider falls back to BLE proximity state plus media-session wake.
 */
@SuppressLint("MissingPermission")
class AacpConnectionManager(private val context: Context) {

    private val _events = Channel<EarbudSignalEvent>(Channel.BUFFERED)
    val events: Flow<EarbudSignalEvent> = _events.receiveAsFlow()

    private var socket: BluetoothSocket? = null
    private var readerThread: Thread? = null
    @Volatile
    private var isRunning = false

    companion object {
        /** Apple AACP L2CAP PSM (Protocol/Service Multiplexer) */
        private const val AACP_PSM = 0x1001

        /** AACP opcodes we care about */
        private const val OPCODE_STEM_PRESS: Byte = 0x19

        /** L2CAP UUID for AirPods accessory protocol */
        private val AACP_UUID: UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
    }

    fun start(device: BluetoothDevice?) {
        if (isRunning) return
        if (device == null) {
            Log.d(TAG, "No bonded device available for AACP; skipping L2CAP")
            return
        }
        isRunning = true
        connect(device)
    }

    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        readerThread = null
    }

    private fun connect(device: BluetoothDevice) {
        readerThread = Thread({
            try {
                // Try to create an L2CAP socket. This requires the device to be bonded.
                // createL2capChannel is API 29+; fallback to createRfcommSocket for older devices.
                val s = try {
                    // Hidden API: createL2capCocSocket(int psm) is available on API 29+ but hidden.
                    // We use reflection to access it. If it fails, we fall back to RFCOMM.
                    val method = BluetoothDevice::class.java.getDeclaredMethod("createL2capCocSocket", Int::class.javaPrimitiveType)
                    method.invoke(device, AACP_PSM) as BluetoothSocket
                } catch (e: Exception) {
                    Log.d(TAG, "Reflection L2CAP failed, falling back to RFCOMM: ${e.message}")
                    device.createRfcommSocketToServiceRecord(AACP_UUID)
                }
                socket = s
                s.connect()
                Log.i(TAG, "L2CAP/AACP socket connected to ${device.address}")
                readLoop(s)
            } catch (e: Exception) {
                Log.w(TAG, "AACPL2CAP connection failed: ${e.message}")
            }
        }, "AacpReader").apply { start() }
    }

    private fun readLoop(socket: BluetoothSocket) {
        val input = try {
            socket.inputStream
        } catch (e: IOException) {
            Log.w(TAG, "Failed to get input stream: ${e.message}")
            return
        }

        val buffer = ByteArray(1024)
        while (isRunning) {
            try {
                val read = input.read(buffer)
                if (read <= 0) {
                    Thread.sleep(50)
                    continue
                }
                val packet = buffer.copyOf(read)
                parseAacpPacket(packet)
            } catch (e: IOException) {
                Log.w(TAG, "L2CAP read error: ${e.message}")
                break
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun parseAacpPacket(data: ByteArray) {
        // AACP packets have a 4-byte header: 0x04 0x00 0x04 0x00
        // followed by opcode (1 byte), length (1-2 bytes), payload.
        if (data.size < 6) return
        if (data[0] != 0x04.toByte() || data[1] != 0x00.toByte() ||
            data[2] != 0x04.toByte() || data[3] != 0x00.toByte()
        ) {
            // Not a standard AACP header; may be encrypted or a different framing.
            return
        }

        val opcode = data[4]
        when (opcode) {
            OPCODE_STEM_PRESS -> parseStemPress(data)
            else -> {
                // Other opcodes (battery, ear detection, ANC, etc.) are ignored here
                // because the BLE proximity path already covers battery and ear state.
            }
        }
    }

    private fun parseStemPress(data: ByteArray) {
        if (data.size < 8) return

        val pressType = when (data[6].toInt()) {
            0x05 -> GestureType.SINGLE_PRESS
            0x06 -> GestureType.DOUBLE_PRESS
            0x07 -> GestureType.TRIPLE_PRESS
            0x08 -> GestureType.LONG_PRESS
            else -> {
                Log.d(TAG, "Unknown stem press type: ${data[6]}")
                return
            }
        }

        val budSide = when (data[7].toInt()) {
            0x01 -> BudSide.LEFT
            0x02 -> BudSide.RIGHT
            else -> null
        }

        Log.i(TAG, "Stem press detected: $pressType on ${budSide?.name ?: "unknown"}")
        val event = when (pressType) {
            GestureType.SINGLE_PRESS, GestureType.LONG_PRESS -> EarbudSignalEvent.WakeGesture(
                providerId = "librepods_airpods",
                deviceId = socket?.remoteDevice?.address,
                gestureType = pressType,
                budSide = budSide,
                confidence = SignalConfidence.PROVEN,
            )
            GestureType.DOUBLE_PRESS -> EarbudSignalEvent.InterruptGesture(
                providerId = "librepods_airpods",
                deviceId = socket?.remoteDevice?.address,
                gestureType = pressType,
                budSide = budSide,
                confidence = SignalConfidence.PROVEN,
            )
            GestureType.TRIPLE_PRESS -> EarbudSignalEvent.ApprovalGesture(
                providerId = "librepods_airpods",
                deviceId = socket?.remoteDevice?.address,
                approved = true,
                gestureType = pressType,
            )
            else -> EarbudSignalEvent.WakeGesture(
                providerId = "librepods_airpods",
                deviceId = socket?.remoteDevice?.address,
                gestureType = pressType,
                budSide = budSide,
                confidence = SignalConfidence.PROVEN,
            )
        }
        _events.trySend(event)
    }
}
