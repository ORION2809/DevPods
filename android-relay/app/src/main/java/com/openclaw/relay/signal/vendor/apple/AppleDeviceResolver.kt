package com.openclaw.relay.signal.vendor.apple

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Apple device resolver derived from CAPod [PodModel] and LibrePods BLE proximity parsing.
 *
 * Maps Apple manufacturer-specific data (ID 0x004C / 76) to known AirPods/Beats models,
 * battery levels, ear state, and lid state.
 */
object AppleDeviceResolver {

    const val APPLE_MANUFACTURER_ID = 0x004C

    private val AIRPODS_MODELS = mapOf(
        // AirPods 1
        0x0220 to AppleModelInfo("AirPods (Gen 1)", hasCase = true, hasEarDetection = true),
        // AirPods 2
        0x0F20 to AppleModelInfo("AirPods (Gen 2)", hasCase = true, hasEarDetection = true),
        0x1320 to AppleModelInfo("AirPods (Gen 2)", hasCase = true, hasEarDetection = true),
        // AirPods 3
        0x1420 to AppleModelInfo("AirPods (Gen 3)", hasCase = true, hasEarDetection = true),
        0x1620 to AppleModelInfo("AirPods (Gen 3)", hasCase = true, hasEarDetection = true),
        // AirPods 4
        0x1720 to AppleModelInfo("AirPods (Gen 4)", hasCase = true, hasEarDetection = true),
        0x1820 to AppleModelInfo("AirPods (Gen 4 ANC)", hasCase = true, hasEarDetection = true, hasAnc = true),
        // AirPods Pro
        0x0E20 to AppleModelInfo("AirPods Pro", hasCase = true, hasEarDetection = true, hasAnc = true),
        0x0720 to AppleModelInfo("AirPods Pro", hasCase = true, hasEarDetection = true, hasAnc = true),
        // AirPods Pro 2
        0x0A20 to AppleModelInfo("AirPods Pro 2", hasCase = true, hasEarDetection = true, hasAnc = true),
        0x0B20 to AppleModelInfo("AirPods Pro 2", hasCase = true, hasEarDetection = true, hasAnc = true),
        0x1B20 to AppleModelInfo("AirPods Pro 2 USB-C", hasCase = true, hasEarDetection = true, hasAnc = true),
        0x1C20 to AppleModelInfo("AirPods Pro 2 USB-C", hasCase = true, hasEarDetection = true, hasAnc = true),
        0x1D20 to AppleModelInfo("AirPods Pro 3", hasCase = true, hasEarDetection = true, hasAnc = true),
        0x1E20 to AppleModelInfo("AirPods Pro 3", hasCase = true, hasEarDetection = true, hasAnc = true),
        // AirPods Max
        0x0A20 to AppleModelInfo("AirPods Max", hasCase = false, hasEarDetection = true, hasAnc = true),
        0x0C20 to AppleModelInfo("AirPods Max", hasCase = false, hasEarDetection = true, hasAnc = true),
        // Beats
        0x0520 to AppleModelInfo("Beats Solo 3", hasCase = false),
        0x1020 to AppleModelInfo("Powerbeats Pro", hasCase = true, hasEarDetection = true),
        0x1120 to AppleModelInfo("Powerbeats 4", hasCase = false),
        0x0320 to AppleModelInfo("BeatsX", hasCase = false),
        0x0620 to AppleModelInfo("Beats Studio 3", hasCase = false, hasAnc = true),
        0x0920 to AppleModelInfo("Beats Flex", hasCase = false),
        0x0D20 to AppleModelInfo("Beats Studio Buds", hasCase = true, hasAnc = true),
        0x1520 to AppleModelInfo("Beats Fit Pro", hasCase = true, hasEarDetection = true, hasAnc = true),
        0x1920 to AppleModelInfo("Beats Studio Buds+", hasCase = true, hasAnc = true),
        0x1A20 to AppleModelInfo("Beats Solo 4", hasCase = false),
        0x2120 to AppleModelInfo("Beats Solo Buds", hasCase = true),
        0x2320 to AppleModelInfo("Powerbeats Pro 2", hasCase = true, hasEarDetection = true, hasAnc = true),
        0x2420 to AppleModelInfo("Beats Studio Pro", hasCase = false, hasAnc = true),
        0x2520 to AppleModelInfo("Beats Pill", hasCase = false),
    )

    data class AppleModelInfo(
        val modelName: String,
        val hasCase: Boolean = false,
        val hasEarDetection: Boolean = false,
        val hasAnc: Boolean = false,
    )

    data class ProximitySnapshot(
        val address: String,
        val modelId: Int,
        val modelName: String,
        val leftBatteryPercent: Int?,
        val rightBatteryPercent: Int?,
        val caseBatteryPercent: Int?,
        val isLeftInEar: Boolean?,
        val isRightInEar: Boolean?,
        val isCaseOpen: Boolean?,
        val isLeftCharging: Boolean?,
        val isRightCharging: Boolean?,
        val isCaseCharging: Boolean?,
        val rssi: Int?,
    )

    fun resolveFromScanRecord(address: String, scanRecord: ScanRecord?, rssi: Int?): ProximitySnapshot? {
        val data = scanRecord?.getManufacturerSpecificData(APPLE_MANUFACTURER_ID) ?: return null
        return parseProximityData(address, data, rssi)
    }

    fun parseProximityData(address: String, data: ByteArray, rssi: Int?): ProximitySnapshot? {
        if (data.size < 7) return null

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val modelId = buffer.short.toInt() and 0xFFFF

        val modelInfo = AIRPODS_MODELS[modelId]
            ?: AppleModelInfo("Unknown Apple device (0x${modelId.toString(16)})")

        val statusByte = if (data.size > 2) data[2].toInt() and 0xFF else 0

        // Battery encoding from OpenPods / CAPod / LibrePods:
        // Bits 0-3: left battery (0-10 scale, 0 = disconnected)
        // Bits 4-7: right battery (0-10 scale, 0 = disconnected)
        val leftBatteryRaw = statusByte and 0x0F
        val rightBatteryRaw = (statusByte shr 4) and 0x0F

        // Case battery is in byte 3 if present
        val caseBatteryRaw = if (data.size > 3) data[3].toInt() and 0x0F else 0

        // Charging flags from byte 4 if present
        val chargeByte = if (data.size > 4) data[4].toInt() and 0xFF else 0
        val isLeftCharging = (chargeByte and 0x01) != 0
        val isRightCharging = (chargeByte and 0x02) != 0
        val isCaseCharging = (chargeByte and 0x04) != 0

        // In-ear / lid detection from byte 5 if present
        val inEarByte = if (data.size > 5) data[5].toInt() and 0xFF else 0
        val isLeftInEar = (inEarByte and 0x01) != 0
        val isRightInEar = (inEarByte and 0x02) != 0
        val isCaseOpen = (inEarByte and 0x04) != 0

        return ProximitySnapshot(
            address = address,
            modelId = modelId,
            modelName = modelInfo.modelName,
            leftBatteryPercent = if (leftBatteryRaw in 1..10) leftBatteryRaw * 10 else null,
            rightBatteryPercent = if (rightBatteryRaw in 1..10) rightBatteryRaw * 10 else null,
            caseBatteryPercent = if (caseBatteryRaw in 1..10) caseBatteryRaw * 10 else null,
            isLeftInEar = if (modelInfo.hasEarDetection) isLeftInEar else null,
            isRightInEar = if (modelInfo.hasEarDetection) isRightInEar else null,
            isCaseOpen = if (modelInfo.hasCase) isCaseOpen else null,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = if (modelInfo.hasCase) isCaseCharging else null,
            rssi = rssi,
        )
    }

    fun isAppleProximity(scanRecord: ScanRecord?): Boolean {
        return scanRecord?.getManufacturerSpecificData(APPLE_MANUFACTURER_ID) != null
    }
}
