package com.openclaw.relay.signal.vendor.oppo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
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

/**
 * Oppo / Realme / OPlus headphones provider.
 * Detects Oppo Enco and Realme Buds families by bonded device name.
 */
@SuppressLint("MissingPermission")
class OppoRealmeProvider(private val context: Context) : EarbudSignalProvider {

    override val providerId: String = "oppo_realme"
    override val providerLabel: String = "Oppo / Realme"
    override val isPhysicalInput: Boolean = true
    override val defaultConfidence: SignalConfidence = SignalConfidence.UNPROVEN

    private val _capabilityProfile = MutableStateFlow(
        EarbudCapabilityProfile(
            providerId = providerId,
            deviceModel = null,
            capabilities = listOf(Capability.BATTERY_STATUS, Capability.WAKE_SINGLE_PRESS),
            wakeGestures = mapOf(GestureType.SINGLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED),
            interruptGestures = emptyMap(),
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
        val device = findOppoRealmeDevice()
        if (device != null) connectToDevice(device)
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
        val device = findOppoRealmeDevice()
        return if (device != null) {
            EarbudSignalProvider.ProbeResult(
                success = true,
                detectedDevice = true,
                detectedGestures = emptyList(),
                message = "${device.name ?: "Oppo/Realme buds"} detected.",
            )
        } else {
            EarbudSignalProvider.ProbeResult(
                success = true,
                detectedDevice = false,
                detectedGestures = emptyList(),
                message = "No Oppo/Realme device found.",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun findOppoRealmeDevice(): BluetoothDevice? {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val adapter = btManager.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bondedDevices.firstOrNull { dev ->
            val name = dev.name ?: return@firstOrNull false
            OPPO_REALME_PATTERNS.any { name.contains(it, ignoreCase = true) }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        val newTransport = BtClassicSerialTransport(context, device.address, UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
        transport = newTransport
        scope.launch {
            newTransport.state.collect { state ->
                when (state) {
                    TransportState.CONNECTED -> {
                        _deviceState.value = EarbudDeviceState(
                            providerId = providerId,
                            deviceId = device.address,
                            displayName = device.name ?: "Oppo / Realme",
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
                        _deviceState.value = null
                    }
                    else -> {}
                }
            }
        }
        newTransport.start()
    }

    companion object {
        private val OPPO_REALME_PATTERNS = listOf(
            "Oppo Enco", "OPPO Enco",
            "Realme Buds", "realme Buds",
            "OnePlus Buds", "ONEPLUS Buds",
        )
    }
}
