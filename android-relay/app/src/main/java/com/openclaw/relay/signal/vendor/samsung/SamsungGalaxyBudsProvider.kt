package com.openclaw.relay.signal.vendor.samsung

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
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

private const val TAG = "SamsungBudsProvider"
private val GALAXY_BUDS_CTRL_UUID: UUID = UUID.fromString("00001102-0000-1000-8000-00805f9b34fd")
private val GALAXY_BUDS_LIVE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

@SuppressLint("MissingPermission")
class SamsungGalaxyBudsProvider(private val context: Context) : EarbudSignalProvider {

    override val providerId: String = "samsung_galaxy_buds"
    override val providerLabel: String = "Samsung Galaxy Buds"
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
                Capability.APPROVE_DOUBLE_PRESS,
            ),
            wakeGestures = mapOf(
                GestureType.SINGLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED,
            ),
            interruptGestures = mapOf(
                GestureType.DOUBLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED,
            ),
            approvalGestures = mapOf(
                GestureType.TRIPLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED,
            ),
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

    private var detectedDeviceAddress: String? = null
    private var detectedDeviceName: String? = null
    private var frameFormat = SamsungPacketCodec.FrameFormat.PLUS

    override suspend fun start() {
        if (isStarted) return
        isStarted = true

        val bondedDevice = findGalaxyBudsDevice()
        if (bondedDevice != null) {
            connectToDevice(bondedDevice)
        } else {
            Log.i(TAG, "No Galaxy Buds bonded; will probe for detection")
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
        val bonded = findGalaxyBudsDevice()
        return if (bonded != null) {
            detectedDeviceAddress = bonded.address
            detectedDeviceName = bonded.name
            frameFormat = SamsungPacketCodec.detectFormatFromDeviceName(bonded.name)
            EarbudSignalProvider.ProbeResult(
                success = true,
                detectedDevice = true,
                detectedGestures = listOf(
                    EarbudSignalProvider.GestureDetected(
                        gestureType = GestureType.SINGLE_PRESS,
                        budSide = null,
                        confidence = SignalConfidence.UNPROVEN,
                    ),
                    EarbudSignalProvider.GestureDetected(
                        gestureType = GestureType.DOUBLE_PRESS,
                        budSide = null,
                        confidence = SignalConfidence.UNPROVEN,
                    ),
                ),
                message = "${bonded.name ?: "Galaxy Buds"} bonded. Touch controls must be configured to send media events.",
            )
        } else {
            EarbudSignalProvider.ProbeResult(
                success = true,
                detectedDevice = false,
                detectedGestures = emptyList(),
                message = "No Galaxy Buds found. Use MediaSession fallback for Samsung earbuds.",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun findGalaxyBudsDevice(): BluetoothDevice? {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return null
        if (!adapter.isEnabled) return null

        return adapter.bondedDevices.firstOrNull { device ->
            val name = device.name ?: return@firstOrNull false
            GALAXY_BUDS_NAME_PATTERNS.any { pattern ->
                name.contains(pattern, ignoreCase = true)
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        val address = device.address
        detectedDeviceAddress = address
        detectedDeviceName = device.name
        frameFormat = SamsungPacketCodec.detectFormatFromDeviceName(device.name)

        val uuid = if (device.name?.contains("Live", ignoreCase = true) == true)
            GALAXY_BUDS_LIVE_UUID else GALAXY_BUDS_CTRL_UUID

        val newTransport = BtClassicSerialTransport(context, address, uuid)
        transport = newTransport

        scope.launch {
            newTransport.state.collect { state ->
                when (state) {
                    TransportState.CONNECTED -> {
                        _deviceState.value = EarbudDeviceState(
                            providerId = providerId,
                            deviceId = address,
                            displayName = device.name ?: "Galaxy Buds",
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
                                deviceId = address,
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
                                    deviceId = address,
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

        scope.launch {
            newTransport.receivedPackets.collect { packet ->
                handleReceivedPacket(packet)
            }
        }

        newTransport.start()
    }

    private fun handleReceivedPacket(raw: ByteArray) {
        val packet = SamsungPacketCodec.decodePacket(raw) ?: return
        when (packet.messageId) {
            SamsungPacketCodec.BATTERY_STATUS,
            SamsungPacketCodec.BATTERY_STATUS2 -> {
                val battery = SamsungPacketCodec.decodeBattery(packet)
                val batteryState = EarbudBatteryState(
                    leftPercent = battery.leftPercent,
                    rightPercent = battery.rightPercent,
                    casePercent = battery.casePercent,
                    isLow = (battery.leftPercent ?: 100) <= 15 ||
                        (battery.rightPercent ?: 100) <= 15,
                )
                _deviceState.value = _deviceState.value?.copy(battery = batteryState)
                _events.trySend(
                    EarbudSignalEvent.BatteryChanged(
                        providerId = providerId,
                        deviceId = detectedDeviceAddress,
                        leftPercent = battery.leftPercent,
                        rightPercent = battery.rightPercent,
                        casePercent = battery.casePercent,
                    )
                )
            }
        }
    }

    companion object {
        private val GALAXY_BUDS_NAME_PATTERNS = listOf(
            "Galaxy Buds",
            "Galaxy Buds2",
            "Galaxy Buds3",
            "Galaxy Buds Live",
            "Galaxy Buds Pro",
            "Galaxy Buds FE",
            "SM-R170",
            "SM-R180",
            "SM-R190",
            "SM-R177",
            "SM-R510",
            "SM-R400",
            "SM-R630",
        )
    }
}
