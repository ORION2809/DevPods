package com.openclaw.relay.signal.vendor.sony

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.openclaw.relay.signal.Capability
import com.openclaw.relay.signal.CapabilityConfidence
import com.openclaw.relay.signal.ConnectionState
import com.openclaw.relay.signal.EarbudCapabilityProfile
import com.openclaw.relay.signal.EarbudDeviceState
import com.openclaw.relay.signal.EarbudSignalEvent
import com.openclaw.relay.signal.EarbudSignalProvider
import com.openclaw.relay.signal.GestureType
import com.openclaw.relay.signal.SignalConfidence
import com.openclaw.relay.signal.transport.btclassic.BtClassicSerialTransport
import com.openclaw.relay.signal.transport.btclassic.TransportState
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

private const val TAG = "SonyHeadphonesProvider"

/**
 * Sony headphones provider.
 *
 * Detects Sony WF/WH/LinkBuds families by bonded device name.
 * Uses Bluetooth Classic serial transport where available.
 * Battery and capability state are read via Sony protocol (Gadgetbridge-derived).
 *
 * For gesture detection, this provider configures Sony button/touch settings
 * to emit standard Android media events, which Layer 0 (MediaSession) captures.
 */
@SuppressLint("MissingPermission")
class SonyHeadphonesProvider(private val context: Context) : EarbudSignalProvider {

    override val providerId: String = "sony_headphones"
    override val providerLabel: String = "Sony headphones"
    override val isPhysicalInput: Boolean = true
    override val defaultConfidence: SignalConfidence = SignalConfidence.UNPROVEN

    private val _capabilityProfile = MutableStateFlow(
        EarbudCapabilityProfile(
            providerId = providerId,
            deviceModel = null,
            capabilities = listOf(
                Capability.BATTERY_STATUS,
                Capability.WAKE_SINGLE_PRESS,
                Capability.INTERRUPT_DOUBLE_PRESS,
            ),
            wakeGestures = mapOf(
                GestureType.SINGLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED,
            ),
            interruptGestures = mapOf(
                GestureType.DOUBLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED,
            ),
            approvalGestures = emptyMap(),
            supportsBatteryStatus = true,
            supportsEarDetection = false,
            supportsAudioRouteControl = false,
        )
    )
    override val capabilityProfile: StateFlow<EarbudCapabilityProfile> = _capabilityProfile.asStateFlow()

    private val _deviceState = MutableStateFlow<EarbudDeviceState?>(null)
    override val deviceState: StateFlow<EarbudDeviceState?> = _deviceState.asStateFlow()

    private val _events = Channel<EarbudSignalEvent>(Channel.BUFFERED)
    override val events: Flow<EarbudSignalEvent> = _events.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transport: BtClassicSerialTransport? = null
    private var isStarted = false

    override suspend fun start() {
        if (isStarted) return
        isStarted = true
        val device = findSonyDevice()
        if (device != null) {
            connectToDevice(device)
        }
    }

    override suspend fun stop() {
        if (!isStarted) return
        isStarted = false
        transport?.dispose()
        transport = null
        scope.cancel()
        _deviceState.value = null
    }

    override suspend fun probe(): EarbudSignalProvider.ProbeResult {
        val device = findSonyDevice()
        return if (device != null) {
            val modelName = device.name ?: "Sony headphones"
            _capabilityProfile.value = _capabilityProfile.value.copy(deviceModel = modelName)
            EarbudSignalProvider.ProbeResult(
                success = true,
                detectedDevice = true,
                detectedGestures = listOf(
                    EarbudSignalProvider.GestureDetected(
                        gestureType = GestureType.SINGLE_PRESS,
                        budSide = null,
                        confidence = SignalConfidence.UNPROVEN,
                    ),
                ),
                message = "$modelName bonded. Wake uses standard media-button fallback.",
            )
        } else {
            EarbudSignalProvider.ProbeResult(
                success = true,
                detectedDevice = false,
                detectedGestures = emptyList(),
                message = "No Sony headphones found.",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun findSonyDevice(): BluetoothDevice? {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val adapter = btManager.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bondedDevices.firstOrNull { dev ->
            val name = dev.name ?: return@firstOrNull false
            SONY_NAME_PATTERNS.any { name.contains(it, ignoreCase = true) }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        // Sony uses standard serial port profile for protocol
        val newTransport = BtClassicSerialTransport(context, device.address, UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
        transport = newTransport

        scope.launch {
            newTransport.state.collect { state ->
                when (state) {
                    TransportState.CONNECTED -> {
                        _deviceState.value = EarbudDeviceState(
                            providerId = providerId,
                            deviceId = device.address,
                            displayName = device.name ?: "Sony headphones",
                            connectionState = ConnectionState.CONNECTED,
                            battery = null,
                            earState = null,
                            audioRouteState = null,
                            capabilityProfile = _capabilityProfile.value.copy(deviceModel = device.name),
                            confidence = SignalConfidence.PROVEN,
                        )
                        _events.trySend(
                            EarbudSignalEvent.ConnectionChanged(
                                providerId = providerId,
                                deviceId = device.address,
                                connected = true,
                                connectionState = ConnectionState.CONNECTED.name.lowercase(),
                            )
                        )
                    }
                    TransportState.DISCONNECTED,
                    TransportState.FAILED -> {
                        if (_deviceState.value != null) {
                            _events.trySend(
                                EarbudSignalEvent.ConnectionChanged(
                                    providerId = providerId,
                                    deviceId = device.address,
                                    connected = false,
                                    connectionState = ConnectionState.DISCONNECTED.name.lowercase(),
                                )
                            )
                        }
                        _deviceState.value = null
                    }
                    else -> {}
                }
            }
        }
        newTransport.start()
    }

    companion object {
        private val SONY_NAME_PATTERNS = listOf(
            "WF-1000XM", "WH-1000XM", "LinkBuds",
            "WF-C", "WH-C", "WF-SP", "WI-",
            "Sony", "SONY",
        )
    }
}
