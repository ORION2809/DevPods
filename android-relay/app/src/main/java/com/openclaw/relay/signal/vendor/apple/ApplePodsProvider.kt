package com.openclaw.relay.signal.vendor.apple

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.openclaw.relay.signal.BudSide
import com.openclaw.relay.signal.Capability
import com.openclaw.relay.signal.CapabilityConfidence
import com.openclaw.relay.signal.ConnectionState
import com.openclaw.relay.signal.EarbudBatteryState
import com.openclaw.relay.signal.EarbudCapabilityProfile
import com.openclaw.relay.signal.EarbudDeviceState
import com.openclaw.relay.signal.EarbudSignalEvent
import com.openclaw.relay.signal.EarbudSignalProvider
import com.openclaw.relay.signal.EarState
import com.openclaw.relay.signal.GestureType
import com.openclaw.relay.signal.SignalConfidence
import com.openclaw.relay.signal.transport.ble.L2capAapTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "ApplePodsProvider"

/**
 * Rich AirPods/Beats provider derived from CAPod and LibrePods source knowledge.
 *
 * Features:
 * - BLE proximity scanning with Apple model ID resolution
 * - L2CAP/AACP stem-press event capture
 * - Battery, ear-state, lid-state, charging-state tracking
 * - Dynamic capability confidence based on observed events
 */
@SuppressLint("MissingPermission")
class ApplePodsProvider(private val context: Context) : EarbudSignalProvider {

    override val providerId: String = "apple_airpods"
    override val providerLabel: String = "Apple AirPods / Beats"
    override val isPhysicalInput: Boolean = true
    override val defaultConfidence: SignalConfidence = SignalConfidence.UNPROVEN

    private val _capabilityProfile = MutableStateFlow(
        EarbudCapabilityProfile(
            providerId = providerId,
            deviceModel = null,
            capabilities = listOf(
                Capability.BATTERY_STATUS,
                Capability.IN_EAR_DETECTION,
                Capability.STEM_PRESS_EVENTS,
                Capability.WAKE_SINGLE_PRESS,
                Capability.WAKE_LONG_PRESS,
                Capability.INTERRUPT_DOUBLE_PRESS,
                Capability.APPROVE_DOUBLE_PRESS,
            ),
            wakeGestures = mapOf(
                GestureType.SINGLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED,
                GestureType.LONG_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED,
            ),
            interruptGestures = mapOf(
                GestureType.DOUBLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED,
            ),
            approvalGestures = mapOf(
                GestureType.TRIPLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED,
            ),
            supportsBatteryStatus = true,
            supportsEarDetection = true,
            supportsAudioRouteControl = false,
        )
    )
    override val capabilityProfile: StateFlow<EarbudCapabilityProfile> = _capabilityProfile.asStateFlow()

    private val _deviceState = MutableStateFlow<EarbudDeviceState?>(null)
    override val deviceState: StateFlow<EarbudDeviceState?> = _deviceState.asStateFlow()

    private val _events = Channel<EarbudSignalEvent>(Channel.BUFFERED)
    override val events: Flow<EarbudSignalEvent> = _events.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scanner: BluetoothLeScanner? = null
    private var l2capTransport: L2capAapTransport? = null
    private var isStarted = false

    private var lastSnapshot: AppleDeviceResolver.ProximitySnapshot? = null
    private var lastSeenAtMs: Long = 0
    private val STALE_TIMEOUT_MS = 30000L

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val snapshot = AppleDeviceResolver.resolveFromScanRecord(
                result.device?.address ?: return,
                result.scanRecord,
                result.rssi,
            ) ?: return
            onProximitySnapshot(snapshot)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
        }
    }

    override suspend fun start() {
        if (isStarted) return
        isStarted = true

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable")
            return
        }

        scanner = adapter.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                AppleDeviceResolver.APPLE_MANUFACTURER_ID,
                byteArrayOf(),
                byteArrayOf(),
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "BLUETOOTH_SCAN permission missing")
        }

        Log.i(TAG, "ApplePods provider started")
    }

    override suspend fun stop() {
        if (!isStarted) return
        isStarted = false
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanner = null
        l2capTransport?.dispose()
        l2capTransport = null
        scope.cancel()
        _deviceState.value = null
    }

    override suspend fun probe(): EarbudSignalProvider.ProbeResult {
        val snapshot = lastSnapshot
        return if (snapshot != null && !isStale()) {
            EarbudSignalProvider.ProbeResult(
                success = true,
                detectedDevice = true,
                detectedGestures = capabilityProfile.value.wakeGestures.keys.map { gestureType ->
                    EarbudSignalProvider.GestureDetected(
                        gestureType = gestureType,
                        budSide = null,
                        confidence = SignalConfidence.OBSERVED,
                    )
                },
                message = "${snapshot.modelName} detected via BLE proximity.",
            )
        } else {
            EarbudSignalProvider.ProbeResult(
                success = true,
                detectedDevice = false,
                detectedGestures = emptyList(),
                message = "No AirPods/Beats detected. Media-button fallback available.",
            )
        }
    }

    private fun onProximitySnapshot(snapshot: AppleDeviceResolver.ProximitySnapshot) {
        lastSnapshot = snapshot
        lastSeenAtMs = System.currentTimeMillis()

        val previous = _deviceState.value
        val battery = EarbudBatteryState(
            leftPercent = snapshot.leftBatteryPercent,
            rightPercent = snapshot.rightBatteryPercent,
            casePercent = snapshot.caseBatteryPercent,
            isLow = (snapshot.leftBatteryPercent ?: 100) <= 15 ||
                (snapshot.rightBatteryPercent ?: 100) <= 15,
        )
        val earState = EarState(
            leftInEar = snapshot.isLeftInEar,
            rightInEar = snapshot.isRightInEar,
        )

        _deviceState.value = EarbudDeviceState(
            providerId = providerId,
            deviceId = snapshot.address,
            displayName = snapshot.modelName,
            connectionState = ConnectionState.CONNECTED,
            battery = battery,
            earState = earState,
            audioRouteState = null,
            capabilityProfile = _capabilityProfile.value.copy(deviceModel = snapshot.modelName),
            confidence = SignalConfidence.PROVEN,
            lastSeenAtMs = lastSeenAtMs,
        )
        _capabilityProfile.value = _capabilityProfile.value.copy(deviceModel = snapshot.modelName)

        if (previous?.deviceId != snapshot.address) {
            _events.trySend(
                EarbudSignalEvent.ConnectionChanged(
                    providerId = providerId,
                    deviceId = snapshot.address,
                    connected = true,
                    connectionState = ConnectionState.CONNECTED.name.lowercase(),
                )
            )
            tryStartAap(snapshot.address)
        }

        if (previous?.earState != earState) {
            _events.trySend(
                EarbudSignalEvent.EarStateChanged(
                    providerId = providerId,
                    deviceId = snapshot.address,
                    leftInEar = snapshot.isLeftInEar,
                    rightInEar = snapshot.isRightInEar,
                )
            )
        }
        if (previous?.battery != battery) {
            _events.trySend(
                EarbudSignalEvent.BatteryChanged(
                    providerId = providerId,
                    deviceId = snapshot.address,
                    leftPercent = snapshot.leftBatteryPercent,
                    rightPercent = snapshot.rightBatteryPercent,
                    casePercent = snapshot.caseBatteryPercent,
                )
            )
        }
    }

    private fun tryStartAap(address: String) {
        val existing = l2capTransport
        if (existing != null) return

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bonded = btManager?.adapter?.bondedDevices?.find { it.address == address }
        if (bonded == null) {
            Log.d(TAG, "Device $address not bonded; AAP unavailable")
            return
        }

        val transport = L2capAapTransport(context, address)
        l2capTransport = transport
        scope.launch {
            transport.frames.collect { frame ->
                parseAapFrame(frame)
            }
        }
        transport.start()
    }

    private fun parseAapFrame(frame: ByteArray) {
        if (frame.size < 4) return
        // AACP opcode parsing derived from LibrePods/CAPod
        val opcode = frame[0].toInt() and 0xFF
        when (opcode) {
            0x19 -> { // Stem press event (LibrePods AACPManager opcode)
                val pressType = frame.getOrNull(1)?.toInt()?.and(0xFF) ?: return
                val sideByte = frame.getOrNull(2)?.toInt()?.and(0xFF)
                val budSide = when (sideByte) {
                    0x00 -> BudSide.LEFT
                    0x01 -> BudSide.RIGHT
                    else -> null
                }
                val gesture = when (pressType) {
                    0x01 -> GestureType.SINGLE_PRESS
                    0x02 -> GestureType.DOUBLE_PRESS
                    0x03 -> GestureType.TRIPLE_PRESS
                    0x04 -> GestureType.LONG_PRESS
                    else -> GestureType.UNKNOWN
                }
                val event = when (gesture) {
                    GestureType.SINGLE_PRESS -> EarbudSignalEvent.WakeGesture(
                        providerId = providerId,
                        deviceId = lastSnapshot?.address,
                        gestureType = gesture,
                        budSide = budSide,
                        confidence = SignalConfidence.PROVEN,
                    )
                    GestureType.DOUBLE_PRESS -> EarbudSignalEvent.InterruptGesture(
                        providerId = providerId,
                        deviceId = lastSnapshot?.address,
                        gestureType = gesture,
                        budSide = budSide,
                        confidence = SignalConfidence.PROVEN,
                    )
                    GestureType.TRIPLE_PRESS -> EarbudSignalEvent.ApprovalGesture(
                        providerId = providerId,
                        deviceId = lastSnapshot?.address,
                        approved = true,
                        gestureType = gesture,
                    )
                    GestureType.LONG_PRESS -> EarbudSignalEvent.WakeGesture(
                        providerId = providerId,
                        deviceId = lastSnapshot?.address,
                        gestureType = gesture,
                        budSide = budSide,
                        confidence = SignalConfidence.PROVEN,
                    )
                    else -> null
                }
                event?.let {
                    _events.trySend(it)
                    markGestureProven(gesture)
                }
            }
        }
    }

    private fun markGestureProven(gesture: GestureType) {
        val current = _capabilityProfile.value
        _capabilityProfile.value = current.copy(
            wakeGestures = promoteIfMatched(current.wakeGestures, gesture),
            interruptGestures = promoteIfMatched(current.interruptGestures, gesture),
            approvalGestures = promoteIfMatched(current.approvalGestures, gesture),
        )
    }

    private fun promoteIfMatched(
        map: Map<GestureType, CapabilityConfidence>,
        gesture: GestureType,
    ): Map<GestureType, CapabilityConfidence> = map.mapValues {
        if (it.key == gesture && it.value == CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED) {
            CapabilityConfidence.PROVEN
        } else it.value
    }

    private fun isStale(): Boolean {
        return System.currentTimeMillis() - lastSeenAtMs > STALE_TIMEOUT_MS
    }
}
