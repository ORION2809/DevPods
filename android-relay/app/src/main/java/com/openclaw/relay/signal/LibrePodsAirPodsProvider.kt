package com.openclaw.relay.signal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
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

private const val TAG = "LibrePodsProvider"

/**
 * LibrePods-backed provider for AirPods-class devices.
 *
 * Uses a clean-room BLE proximity scanner for battery, ear state, and model
 * detection, plus a best-effort L2CAP/AACP layer for stem-press events.
 *
 * The provider follows the [EarbudSignalProvider] boundary so no AACP or BLE
 * details leak into the relay service or bridge.
 */
@SuppressLint("MissingPermission")
class LibrePodsAirPodsProvider(
    private val context: Context,
) : EarbudSignalProvider {
    override val providerId: String = "librepods_airpods"
    override val providerLabel: String = "LibrePods native"
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

    private val scanner = AirPodsBleScanner(context)
    private val aacpManager = AacpConnectionManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile
    private var isStarted = false

    init {
        scanner.setListener(object : AirPodsBleScanner.Listener {
            override fun onDeviceAppeared(state: AirPodsBleScanner.ScannedDeviceState) {
                updateDeviceState(state)
                _events.trySend(
                    EarbudSignalEvent.ConnectionChanged(
                        providerId = providerId,
                        deviceId = state.address,
                        connected = true,
                        connectionState = ConnectionState.CONNECTED.name.lowercase(),
                    )
                )
                tryStartAacp(state.address)
            }

            override fun onDeviceStateChanged(state: AirPodsBleScanner.ScannedDeviceState) {
                val previous = _deviceState.value
                updateDeviceState(state)
                if (previous?.earState != _deviceState.value?.earState) {
                    _events.trySend(
                        EarbudSignalEvent.EarStateChanged(
                            providerId = providerId,
                            deviceId = state.address,
                            leftInEar = _deviceState.value?.earState?.leftInEar,
                            rightInEar = _deviceState.value?.earState?.rightInEar,
                        )
                    )
                }
                if (previous?.battery != _deviceState.value?.battery) {
                    _events.trySend(
                        EarbudSignalEvent.BatteryChanged(
                            providerId = providerId,
                            deviceId = state.address,
                            leftPercent = _deviceState.value?.battery?.leftPercent,
                            rightPercent = _deviceState.value?.battery?.rightPercent,
                            casePercent = _deviceState.value?.battery?.casePercent,
                        )
                    )
                }
            }

            override fun onDeviceDisappeared() {
                _deviceState.value?.let { current ->
                    _events.trySend(
                        EarbudSignalEvent.ConnectionChanged(
                            providerId = providerId,
                            deviceId = current.deviceId,
                            connected = false,
                            connectionState = ConnectionState.DISCONNECTED.name.lowercase(),
                        )
                    )
                }
                _deviceState.value = null
                aacpManager.stop()
            }

            override fun onScanError(errorCode: Int) {
                Log.w(TAG, "BLE scan error: $errorCode")
            }
        })
    }

    override suspend fun start() {
        if (isStarted) return
        isStarted = true
        try {
            scanner.startScanning()
        } catch (error: SecurityException) {
            Log.w(TAG, "BLE scanning unavailable until BLUETOOTH_SCAN is granted: ${error.message}")
        }
        scope.launch {
            aacpManager.events.collect { event ->
                _events.trySend(event)
            }
        }
        Log.i(TAG, "LibrePods provider started")
    }

    override suspend fun stop() {
        if (!isStarted) return
        isStarted = false
        scanner.stopScanning()
        aacpManager.stop()
        scope.cancel()
        _deviceState.value = null
    }

    override suspend fun probe(): EarbudSignalProvider.ProbeResult {
        val device = scanner.getMostRecentDevice()
        return if (device != null) {
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
                message = "${device.modelName} detected via BLE proximity.",
            )
        } else {
            EarbudSignalProvider.ProbeResult(
                success = true,
                detectedDevice = false,
                detectedGestures = emptyList(),
                message = "No AirPods detected via BLE scanning. Media-button fallback will be used.",
            )
        }
    }

    private fun updateDeviceState(scanned: AirPodsBleScanner.ScannedDeviceState) {
        val battery = EarbudBatteryState(
            leftPercent = scanned.leftBatteryPercent,
            rightPercent = scanned.rightBatteryPercent,
            casePercent = scanned.caseBatteryPercent,
            isLow = (scanned.leftBatteryPercent ?: 100) <= 15 ||
                (scanned.rightBatteryPercent ?: 100) <= 15,
        )
        val earState = EarState(
            leftInEar = scanned.isLeftInEar,
            rightInEar = scanned.isRightInEar,
        )
        _deviceState.value = EarbudDeviceState(
            providerId = providerId,
            deviceId = scanned.address,
            displayName = scanned.modelName,
            connectionState = ConnectionState.CONNECTED,
            battery = battery,
            earState = earState,
            audioRouteState = null,
            capabilityProfile = _capabilityProfile.value.copy(deviceModel = scanned.modelName),
            confidence = SignalConfidence.PROVEN,
        )
        _capabilityProfile.value = _capabilityProfile.value.copy(deviceModel = scanned.modelName)
    }

    private fun tryStartAacp(address: String) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bondedDevice = btManager?.adapter?.bondedDevices?.find { it.address == address }
        if (bondedDevice != null) {
            aacpManager.start(bondedDevice)
        } else {
            Log.d(TAG, "Device $address is not bonded; AACP L2CAP unavailable")
        }
    }
}
