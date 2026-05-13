package com.openclaw.relay.signal

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

private const val TAG = "GenericBtHeadsetProvider"

/**
 * Generic Bluetooth headset provider that detects ANY connected Bluetooth audio device
 * (not just AirPods) via the standard Android BluetoothHeadset and AudioManager APIs.
 *
 * This provider does not require BLE scanning or manufacturer-specific parsing.
 * It works with all Bluetooth earbuds, headphones, and headsets that Android recognizes
 * as audio devices.
 */
class GenericBluetoothHeadsetProvider(
    private val context: Context,
) : EarbudSignalProvider {
    override val providerId: String = "generic_bluetooth_headset"
    override val providerLabel: String = "Bluetooth headset"
    override val isPhysicalInput: Boolean = true
    override val defaultConfidence: SignalConfidence = SignalConfidence.UNPROVEN

    private val _capabilityProfile = MutableStateFlow(
        EarbudCapabilityProfile(
            providerId = providerId,
            deviceModel = null,
            capabilities = listOf(
                Capability.WAKE_SINGLE_PRESS,
                Capability.INTERRUPT_DOUBLE_PRESS,
                Capability.APPROVE_DOUBLE_PRESS,
            ),
            wakeGestures = mapOf(GestureType.SINGLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED),
            interruptGestures = mapOf(GestureType.DOUBLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED),
            approvalGestures = mapOf(GestureType.TRIPLE_PRESS to CapabilityConfidence.SUPPORTED_BUT_NOT_OBSERVED),
            supportsBatteryStatus = false,
            supportsEarDetection = false,
            supportsAudioRouteControl = true,
        )
    )
    override val capabilityProfile: StateFlow<EarbudCapabilityProfile> = _capabilityProfile.asStateFlow()

    private val _deviceState = MutableStateFlow<EarbudDeviceState?>(null)
    override val deviceState: StateFlow<EarbudDeviceState?> = _deviceState.asStateFlow()

    private val _events = Channel<EarbudSignalEvent>(Channel.BUFFERED)
    override val events: Flow<EarbudSignalEvent> = _events.receiveAsFlow()

    private var bluetoothHeadset: BluetoothHeadset? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let { onDeviceConnected(it) }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let { onDeviceDisconnected(it) }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> device?.let { onDeviceConnected(it) }
                        BluetoothProfile.STATE_DISCONNECTED -> device?.let { onDeviceDisconnected(it) }
                    }
                }
                AudioManager.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", 0)
                    if (state == 0) {
                        checkCurrentAudioDevice()
                    }
                }
            }
        }
    }

    override suspend fun start() {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter

        if (btAdapter == null || !btAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable or disabled")
            return
        }

        btAdapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    bluetoothHeadset = proxy as BluetoothHeadset
                    checkCurrentAudioDevice()
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) {
                    bluetoothHeadset = null
                }
            }
        }, BluetoothProfile.HEADSET)

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
        }
        context.registerReceiver(bluetoothReceiver, filter)

        checkCurrentAudioDevice()
    }

    override suspend fun stop() {
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (_: IllegalArgumentException) {
        }
        bluetoothHeadset = null
        _deviceState.value = null
    }

    override suspend fun probe(): EarbudSignalProvider.ProbeResult {
        val connected = getConnectedDevice()
        return if (connected != null) {
            val deviceName = safeDeviceName(connected)
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
                    EarbudSignalProvider.GestureDetected(
                        gestureType = GestureType.TRIPLE_PRESS,
                        budSide = null,
                        confidence = SignalConfidence.UNPROVEN,
                    ),
                ),
                message = "$deviceName connected.",
            )
        } else {
            EarbudSignalProvider.ProbeResult(
                success = true,
                detectedDevice = false,
                detectedGestures = emptyList(),
                message = "No Bluetooth headset currently connected.",
            )
        }
    }

    private fun checkCurrentAudioDevice() {
        val device = getConnectedDevice()
        if (device != null) {
            onDeviceConnected(device)
        } else {
            onAllDevicesDisconnected()
        }
    }

    private fun getConnectedDevice(): BluetoothDevice? {
        val headset = bluetoothHeadset ?: return null
        return try {
            headset.connectedDevices.firstOrNull()
        } catch (_: SecurityException) {
            null
        }
    }

    private fun onDeviceConnected(device: BluetoothDevice) {
        val name = safeDeviceName(device)
        Log.i(TAG, "Device connected: $name")

        _deviceState.value = EarbudDeviceState(
            providerId = providerId,
            deviceId = device.address,
            displayName = name,
            connectionState = ConnectionState.CONNECTED,
            battery = null,
            earState = null,
            audioRouteState = null,
            capabilityProfile = _capabilityProfile.value.copy(deviceModel = name),
            confidence = SignalConfidence.PROVEN,
        )
        _capabilityProfile.value = _capabilityProfile.value.copy(deviceModel = name)

        _events.trySend(
            EarbudSignalEvent.ConnectionChanged(
                providerId = providerId,
                deviceId = device.address,
                connected = true,
                connectionState = ConnectionState.CONNECTED.name.lowercase(),
            )
        )
    }

    private fun onDeviceDisconnected(device: BluetoothDevice) {
        Log.i(TAG, "Device disconnected: ${safeDeviceName(device)}")
        val current = _deviceState.value
        if (current?.deviceId == device.address) {
            _deviceState.value = null
            _events.trySend(
                EarbudSignalEvent.ConnectionChanged(
                    providerId = providerId,
                    deviceId = device.address,
                    connected = false,
                    connectionState = ConnectionState.DISCONNECTED.name.lowercase(),
                )
            )
        }
        checkCurrentAudioDevice()
    }

    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name ?: "Bluetooth device"
        } catch (_: SecurityException) {
            "Bluetooth device"
        }
    }

    private fun onAllDevicesDisconnected() {
        if (_deviceState.value != null) {
            _deviceState.value = null
            _events.trySend(
                EarbudSignalEvent.ConnectionChanged(
                    providerId = providerId,
                    deviceId = null,
                    connected = false,
                    connectionState = ConnectionState.DISCONNECTED.name.lowercase(),
                )
            )
        }
    }
}
