package com.openclaw.relay.signal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "AirPodsBleScanner"
private const val APPLE_MANUFACTURER_ID = 76
private const val CLEANUP_INTERVAL_MS = 10_000L
private const val STALE_DEVICE_TIMEOUT_MS = 15_000L

/**
 * Clean-room BLE proximity scanner for AirPods-class devices.
 *
 * Parses Apple manufacturer-specific advertisement data to extract
 * battery, ear-state, lid-state, and model information without
 * requiring a bonded L2CAP connection.
 *
 * This is a clean-room implementation derived from observing the
 * AirPods BLE advertisement protocol; it does not link GPL code.
 */
@SuppressLint("MissingPermission")
class AirPodsBleScanner(private val context: Context) {

    data class ScannedDeviceState(
        val address: String,
        val modelName: String,
        val leftBatteryPercent: Int?,
        val rightBatteryPercent: Int?,
        val caseBatteryPercent: Int?,
        val isLeftInEar: Boolean,
        val isRightInEar: Boolean,
        val isLeftCharging: Boolean,
        val isRightCharging: Boolean,
        val isCaseCharging: Boolean,
        val lidOpen: Boolean,
        val connectionState: String,
        val lastSeenAtMs: Long = System.currentTimeMillis(),
    )

    interface Listener {
        fun onDeviceAppeared(state: ScannedDeviceState)
        fun onDeviceStateChanged(state: ScannedDeviceState)
        fun onDeviceDisappeared()
        fun onScanError(errorCode: Int)
    }

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var listener: Listener? = null
    private val deviceMap = mutableMapOf<String, ScannedDeviceState>()
    private val processedAddresses = mutableSetOf<String>()
    private var lastBroadcastTime: Long = 0

    private val modelNames = mapOf(
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 (USB-C)",
        0x0220 to "AirPods 1",
        0x0F20 to "AirPods 2",
        0x1320 to "AirPods 3",
        0x1920 to "AirPods 4",
        0x1B20 to "AirPods 4 (ANC)",
        0x0A20 to "AirPods Max",
        0x1F20 to "AirPods Max (USB-C)",
    )

    private val connStates = mapOf(
        0x00 to "disconnected",
        0x04 to "idle",
        0x05 to "music",
        0x06 to "call",
        0x07 to "ringing",
        0x09 to "hanging_up",
        0xFF to "unknown",
    )

    private val handler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            cleanupStaleDevices()
            handler.postDelayed(this, CLEANUP_INTERVAL_MS)
        }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun startScanning() {
        if (scanner != null && scanCallback != null) {
            stopScanning()
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable or disabled")
            return
        }

        scanner = btAdapter.bluetoothLeScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(500L)
            .build()

        val manufacturerData = ByteArray(27)
        val manufacturerMask = ByteArray(27)
        manufacturerData[0] = 7
        manufacturerData[1] = 25
        manufacturerMask[0] = 0xFF.toByte()
        manufacturerMask[1] = 0xFF.toByte()

        val filter = ScanFilter.Builder()
            .setManufacturerData(APPLE_MANUFACTURER_ID, manufacturerData, manufacturerMask)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                processResult(result)
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                processedAddresses.clear()
                for (result in results) {
                    processResult(result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
                listener?.onScanError(errorCode)
            }
        }

        scanner?.startScan(listOf(filter), settings, scanCallback)
        handler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)
        Log.i(TAG, "BLE scanning started")
    }

    fun stopScanning() {
        try {
            scanner?.stopScan(scanCallback ?: return)
        } catch (_: Exception) {
        }
        scanCallback = null
        scanner = null
        handler.removeCallbacks(cleanupRunnable)
        deviceMap.clear()
        Log.i(TAG, "BLE scanning stopped")
    }

    fun getMostRecentDevice(): ScannedDeviceState? {
        return deviceMap.values.maxByOrNull { it.lastSeenAtMs }
    }

    private fun processResult(result: ScanResult) {
        val address = result.device.address
        if (processedAddresses.contains(address)) {
            return
        }
        processedAddresses.add(address)

        val scanRecord = result.scanRecord ?: return
        val manufacturerData = scanRecord.getManufacturerSpecificData(APPLE_MANUFACTURER_ID) ?: return
        if (manufacturerData.size <= 20) return

        lastBroadcastTime = System.currentTimeMillis()

        val parsed = parseProximityMessage(address, manufacturerData)
        val previous = deviceMap[address]
        deviceMap[address] = parsed

        if (previous == null) {
            listener?.onDeviceAppeared(parsed)
            Log.i(TAG, "New AirPods detected: ${parsed.modelName} at $address")
        } else if (parsed != previous) {
            listener?.onDeviceStateChanged(parsed)
        }
    }

    private fun parseProximityMessage(address: String, data: ByteArray): ScannedDeviceState {
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val modelName = modelNames[modelId] ?: "Unknown AirPods (0x${modelId.toString(16)})"

        val status = data[5].toInt() and 0xFF
        val podsBattery = data[6].toInt() and 0xFF
        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val conn = connStates[data[10].toInt()] ?: "unknown"

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft
        val leftBatteryNibble = if (isFlipped) (podsBattery shr 4) and 0x0F else podsBattery and 0x0F
        val rightBatteryNibble = if (isFlipped) podsBattery and 0x0F else (podsBattery shr 4) and 0x0F
        val caseBatteryNibble = flagsCase and 0x0F
        val flags = (flagsCase shr 4) and 0x0F

        val isLeftCharging = if (isFlipped) (flags and 0x02) != 0 else (flags and 0x01) != 0
        val isRightCharging = if (isFlipped) (flags and 0x01) != 0 else (flags and 0x02) != 0
        val isCaseCharging = (flags and 0x04) != 0
        val lidOpen = ((lid shr 3) and 0x01) == 0

        return ScannedDeviceState(
            address = address,
            modelName = modelName,
            leftBatteryPercent = decodeBatteryNibble(leftBatteryNibble),
            rightBatteryPercent = decodeBatteryNibble(rightBatteryNibble),
            caseBatteryPercent = decodeBatteryNibble(caseBatteryNibble),
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            connectionState = conn,
        )
    }

    private fun decodeBatteryNibble(n: Int): Int? = when (n) {
        in 0x0..0x9 -> n * 10
        in 0xA..0xE -> 100
        else -> null
    }

    private fun cleanupStaleDevices() {
        val now = System.currentTimeMillis()
        val staleCutoff = now - STALE_DEVICE_TIMEOUT_MS
        val hadDevices = deviceMap.isNotEmpty()

        val stale = deviceMap.filter { it.value.lastSeenAtMs < staleCutoff }
        for (key in stale.keys) {
            deviceMap.remove(key)
            Log.d(TAG, "Removed stale device: $key")
        }

        if (hadDevices && deviceMap.isEmpty()) {
            listener?.onDeviceDisappeared()
            Log.i(TAG, "All AirPods devices disappeared")
        }
    }
}
