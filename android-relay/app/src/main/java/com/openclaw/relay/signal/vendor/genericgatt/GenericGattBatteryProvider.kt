package com.openclaw.relay.signal.vendor.genericgatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.openclaw.relay.signal.Capability
import com.openclaw.relay.signal.CapabilityConfidence
import com.openclaw.relay.signal.ConnectionState
import com.openclaw.relay.signal.EarbudBatteryState
import com.openclaw.relay.signal.EarbudCapabilityProfile
import com.openclaw.relay.signal.EarbudDeviceState
import com.openclaw.relay.signal.EarbudSignalEvent
import com.openclaw.relay.signal.EarbudSignalProvider
import com.openclaw.relay.signal.GestureType
import com.openclaw.relay.signal.SignalConfidence
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID

private const val TAG = "GenericGattBatteryProvider"
private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
private val BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

/**
 * Generic BLE GATT Battery Service provider.
 *
 * Reads standard Bluetooth SIG Battery Service from any BLE audio device
 * that exposes it. This covers many modern earbuds including Pixel Buds,
 * some Sony models, and generic BLE headsets.
 */
@SuppressLint("MissingPermission")
class GenericGattBatteryProvider(private val context: Context) : EarbudSignalProvider {

    override val providerId: String = "generic_gatt_battery"
    override val providerLabel: String = "BLE battery service"
    override val isPhysicalInput: Boolean = false
    override val defaultConfidence: SignalConfidence = SignalConfidence.UNPROVEN

    private val _capabilityProfile = MutableStateFlow(
        EarbudCapabilityProfile(
            providerId = providerId,
            deviceModel = null,
            capabilities = listOf(Capability.BATTERY_STATUS),
            wakeGestures = emptyMap(),
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

    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var isStarted = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected to ${gatt.device?.address}")
                    gatt.discoverServices()
                    updateStateConnected(gatt.device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected")
                    _deviceState.value = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(BATTERY_SERVICE_UUID) ?: return
            val char = service.getCharacteristic(BATTERY_LEVEL_CHAR_UUID) ?: return
            gatt.readCharacteristic(char)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID && value.isNotEmpty()) {
                val level = value[0].toInt() and 0xFF
                val battery = EarbudBatteryState(
                    leftPercent = level,
                    rightPercent = null,
                    casePercent = null,
                    isLow = level <= 15,
                )
                _deviceState.value = _deviceState.value?.copy(battery = battery)
                _events.trySend(
                    EarbudSignalEvent.BatteryChanged(
                        providerId = providerId,
                        deviceId = gatt.device?.address,
                        leftPercent = level,
                        rightPercent = null,
                        casePercent = null,
                    )
                )
            }
        }
    }

    override suspend fun start() {
        if (isStarted) return
        isStarted = true
        // Defer GATT connection until probe() finds a candidate device
    }

    override suspend fun stop() {
        if (!isStarted) return
        isStarted = false
        gatt?.close()
        gatt = null
        targetDevice = null
        _deviceState.value = null
    }

    override suspend fun probe(): EarbudSignalProvider.ProbeResult {
        val device = findConnectedBleAudioDevice()
        return if (device != null) {
            targetDevice = device
            if (gatt == null) {
                gatt = device.connectGatt(context, false, gattCallback)
            }
            EarbudSignalProvider.ProbeResult(
                success = true,
                detectedDevice = true,
                detectedGestures = emptyList(),
                message = "${device.name ?: "BLE device"} found. Reading battery service...",
            )
        } else {
            EarbudSignalProvider.ProbeResult(
                success = true,
                detectedDevice = false,
                detectedGestures = emptyList(),
                message = "No BLE audio device with battery service detected.",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun findConnectedBleAudioDevice(): BluetoothDevice? {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val adapter = btManager.adapter ?: return null
        if (!adapter.isEnabled) return null

        return adapter.bondedDevices.firstOrNull { device ->
            val deviceType = device.type
            deviceType == BluetoothDevice.DEVICE_TYPE_LE || deviceType == BluetoothDevice.DEVICE_TYPE_DUAL
        }
    }

    private fun updateStateConnected(device: BluetoothDevice?) {
        _deviceState.value = EarbudDeviceState(
            providerId = providerId,
            deviceId = device?.address,
            displayName = device?.name ?: "BLE headset",
            connectionState = ConnectionState.CONNECTED,
            battery = null,
            earState = null,
            audioRouteState = null,
            capabilityProfile = _capabilityProfile.value.copy(deviceModel = device?.name),
            confidence = SignalConfidence.PROVEN,
        )
        _events.trySend(
            EarbudSignalEvent.ConnectionChanged(
                providerId = providerId,
                deviceId = device?.address,
                connected = true,
                connectionState = ConnectionState.CONNECTED.name.lowercase(),
            )
        )
    }
}
