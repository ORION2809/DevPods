package com.openclaw.relay.signal.vendor.samsung

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Samsung Galaxy Buds packet codec derived from Gadgetbridge [GalaxyBudsProtocol].
 *
 * Supports both original Galaxy Buds frame format (SOM=0xFE, EOM=0xEE)
 * and Buds Plus/Live/Pro/2/2 Pro/3 Pro format (SOM=0xFD, EOM=0xDD).
 */
object SamsungPacketCodec {

    private const val SOM_BUDS: Byte = 0xFE.toByte()
    private const val EOM_BUDS: Byte = 0xEE.toByte()
    private const val SOM_BUDS_PLUS: Byte = 0xFD.toByte()
    private const val EOM_BUDS_PLUS: Byte = 0xDD.toByte()

    // Message IDs (incoming)
    const val BATTERY_STATUS: Byte = 0x60.toByte()
    const val BATTERY_STATUS2: Byte = 0x61.toByte()

    // Message IDs (outgoing)
    const val FIND_DEVICE_START: Byte = 0xA0.toByte()
    const val FIND_DEVICE_STOP: Byte = 0xA1.toByte()
    const val SET_LOCK_TOUCH: Byte = 0x90.toByte()
    const val SET_NOISE_CONTROLS: Byte = 0x78.toByte()
    const val SET_TOUCHPAD_OPTIONS: Byte = 0x92.toByte()
    const val SET_NOISE_REDUCTION_LEVEL: Byte = 0x83.toByte()

    enum class FrameFormat {
        ORIGINAL,   // SOM=0xFE
        PLUS,       // SOM=0xFD
    }

    data class DecodedPacket(
        val messageId: Byte,
        val payload: ByteArray,
        val format: FrameFormat,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DecodedPacket
            return messageId == other.messageId && payload.contentEquals(other.payload) && format == other.format
        }
        override fun hashCode(): Int = 31 * (31 * messageId.toInt() + payload.contentHashCode()) + format.hashCode()
    }

    data class BatteryInfo(
        val leftPercent: Int?,
        val rightPercent: Int?,
        val casePercent: Int?,
    )

    fun decodePacket(data: ByteArray): DecodedPacket? {
        if (data.size < 5) return null
        val format = when (data[0]) {
            SOM_BUDS -> FrameFormat.ORIGINAL
            SOM_BUDS_PLUS -> FrameFormat.PLUS
            else -> return null
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.get() // consume SOM

        val length: Int
        if (format == FrameFormat.PLUS) {
            length = buffer.get().toInt() and 0xFF
            buffer.get() // type
        } else {
            buffer.get() // type
            length = buffer.get().toInt() and 0xFF
        }

        val messageId = buffer.get()
        val payloadSize = length - 3
        if (payloadSize < 0 || buffer.position() + payloadSize + 3 > data.size) return null

        val payload = ByteArray(payloadSize)
        buffer.get(payload)

        // Skip CRC (2 bytes) and verify EOM
        val eomPos = buffer.position() + 2
        if (eomPos < data.size) {
            val eom = data[eomPos]
            val expectedEom = if (format == FrameFormat.PLUS) EOM_BUDS_PLUS else EOM_BUDS
            if (eom != expectedEom) return null
        }

        return DecodedPacket(messageId, payload, format)
    }

    fun encodePacket(format: FrameFormat, messageId: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
        val size = (3 + payload.size).toByte()
        val totalSize = 4 + payload.size + 2 + 1
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(if (format == FrameFormat.PLUS) SOM_BUDS_PLUS else SOM_BUDS)
        if (format == FrameFormat.PLUS) {
            buf.put(size)
            buf.put(0x00) // type = outgoing
        } else {
            buf.put(0x00) // type = outgoing
            buf.put(size)
        }
        buf.put(messageId)
        buf.put(payload)

        // CRC-16 CCITT on messageId + payload
        val crcData = ByteArray(1 + payload.size)
        crcData[0] = messageId
        payload.copyInto(crcData, 1)
        val crc = crc16Ccitt(crcData)
        buf.putShort(crc.toShort())

        buf.put(if (format == FrameFormat.PLUS) EOM_BUDS_PLUS else EOM_BUDS)
        return buf.array()
    }

    fun decodeBattery(packet: DecodedPacket): BatteryInfo {
        return when (packet.messageId) {
            BATTERY_STATUS -> {
                val p = packet.payload
                val left = if (p.size > 0 && p[0] > 0) p[0].toInt() and 0xFF else null
                val right = if (p.size > 1 && p[1] > 0) p[1].toInt() and 0xFF else null
                BatteryInfo(left, right, null)
            }
            BATTERY_STATUS2 -> {
                val p = packet.payload
                // Buds Plus format: case battery at index 3 after reordering
                val left = if (p.size > 1 && p[1] > 0) p[1].toInt() and 0xFF else null
                val right = if (p.size > 2 && p[2] > 0) p[2].toInt() and 0xFF else null
                val caseBat = if (p.size > 5 && p[5] > 0) p[5].toInt() and 0xFF else null
                BatteryInfo(left, right, caseBat)
            }
            else -> BatteryInfo(null, null, null)
        }
    }

    fun encodeFindDevice(format: FrameFormat, start: Boolean): ByteArray {
        return encodePacket(format, if (start) FIND_DEVICE_START else FIND_DEVICE_STOP)
    }

    fun encodeSetNoiseControls(format: FrameFormat, mode: Byte): ByteArray {
        // 0 = Ambient + ANC OFF, 1 = ANC on, 2 = Ambient on
        return encodePacket(format, SET_NOISE_CONTROLS, byteArrayOf(mode))
    }

    fun encodeSetTouchpadOptions(format: FrameFormat, leftMode: Byte, rightMode: Byte): ByteArray {
        return encodePacket(format, SET_TOUCHPAD_OPTIONS, byteArrayOf(leftMode, rightMode))
    }

    fun encodeSetAncLevel(format: FrameFormat, level: Byte): ByteArray {
        // 0 = low, 1 = high
        return encodePacket(format, SET_NOISE_REDUCTION_LEVEL, byteArrayOf(level))
    }

    // CRC-16 CCITT (0xFFFF)
    private fun crc16Ccitt(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            var x = crc shr 8 xor (b.toInt() and 0xFF)
            x = x xor (x shr 4)
            crc = (crc shl 8) xor (x shl 12) xor (x shl 5) xor x
            crc = crc and 0xFFFF
        }
        return crc
    }

    fun detectFormatFromDeviceName(name: String?): FrameFormat {
        return when {
            name.isNullOrBlank() -> FrameFormat.PLUS
            name.contains("Galaxy Buds (SM-R170)", ignoreCase = true) -> FrameFormat.ORIGINAL
            else -> FrameFormat.PLUS
        }
    }
}
