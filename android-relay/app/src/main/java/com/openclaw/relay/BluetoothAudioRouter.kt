package com.openclaw.relay

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

class BluetoothAudioRouter(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun routeCommunicationAudio(): RelayAudioRouteSnapshot {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val availableDevices = audioManager.availableCommunicationDevices
        val targetDevice = availableDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        } ?: return RelayAudioRouteSnapshot(
            status = "No communication headset detected",
            availableDevices = describeDevices(availableDevices),
        )

        val routed = audioManager.setCommunicationDevice(targetDevice)
        return RelayAudioRouteSnapshot(
            isActive = routed,
            status = if (routed) "Routed for communication" else "Route request failed",
            selectedDeviceName = targetDevice.productName?.toString()?.takeIf { value -> value.isNotBlank() },
            selectedDeviceType = describeDeviceType(targetDevice.type),
            availableDevices = describeDevices(availableDevices),
        )
    }

    fun snapshot(): RelayAudioRouteSnapshot {
        val currentDevice = audioManager.communicationDevice
        val availableDevices = audioManager.availableCommunicationDevices
        return RelayAudioRouteSnapshot(
            isActive = currentDevice != null,
            status = if (currentDevice != null) "Communication device selected" else "No active communication route",
            selectedDeviceName = currentDevice?.productName?.toString()?.takeIf { value -> value.isNotBlank() },
            selectedDeviceType = currentDevice?.let { describeDeviceType(it.type) },
            availableDevices = describeDevices(availableDevices),
        )
    }

    fun clear() {
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun describeDevices(devices: List<AudioDeviceInfo>): String {
        if (devices.isEmpty()) {
            return "none"
        }

        return devices.joinToString(separator = ", ") { device ->
            val name = device.productName?.toString()?.takeIf { value -> value.isNotBlank() }
            listOfNotNull(describeDeviceType(device.type), name).joinToString(separator = " ")
        }
    }

    private fun describeDeviceType(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            else -> "Device type $type"
        }
    }
}