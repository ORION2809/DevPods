package com.openclaw.relay

import android.media.AudioDeviceInfo

data class RelayAudioDeviceDescriptor(
    val type: Int,
    val productName: String?,
)

object RelayAudioDeviceCatalog {
    fun isCommunicationCaptureDevice(device: RelayAudioDeviceDescriptor?): Boolean {
        if (device == null) {
            return false
        }

        return device.type in setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }

    fun communicationRouteStatus(
        currentCommunicationDevice: RelayAudioDeviceDescriptor?,
        discoveredDevices: List<RelayAudioDeviceDescriptor>,
        routeRequested: Boolean? = null,
    ): String {
        val hasNamedBluetoothOutput = discoveredDevices.any(::isNamedBluetoothOutput)

        if (currentCommunicationDevice == null) {
            return when {
                routeRequested == false -> "Route request failed"
                hasNamedBluetoothOutput -> "Headset output is visible, but no communication microphone route is active"
                else -> "No active communication route"
            }
        }

        if (isCommunicationCaptureDevice(currentCommunicationDevice)) {
            return if (routeRequested == true) {
                "Headset microphone route ready"
            } else {
                "Communication device selected"
            }
        }

        if (hasNamedBluetoothOutput) {
            return "Headset output is visible, but the microphone route stayed on built-in audio"
        }

        return if (routeRequested == false) {
            "Route request failed"
        } else {
            "Communication route is using built-in audio"
        }
    }

    fun availableDeviceSummary(
        communicationDevices: List<RelayAudioDeviceDescriptor>,
        discoveredDevices: List<RelayAudioDeviceDescriptor>,
    ): String {
        val hasNamedBluetoothOutput = discoveredDevices.any(::isNamedBluetoothOutput)
        val labels = (discoveredDevices + communicationDevices)
            // Prefer the named A2DP or BLE output over generic SCO placeholders in the UI.
            .filterNot { device -> hasNamedBluetoothOutput && isPlaceholderBluetoothTransport(device) }
            .distinctBy(::deviceKey)
            .sortedWith(compareBy({ devicePriority(it.type) }, ::describeDevice))
            .map(::describeDevice)

        return labels.joinToString(separator = ", ").ifBlank { "none" }
    }

    fun preferredSelectedDevice(
        currentDevice: RelayAudioDeviceDescriptor?,
        communicationDevices: List<RelayAudioDeviceDescriptor>,
        discoveredDevices: List<RelayAudioDeviceDescriptor>,
    ): RelayAudioDeviceDescriptor? {
        val namedBluetoothOutput = discoveredDevices.firstOrNull(::isNamedBluetoothOutput)
        if (namedBluetoothOutput != null && shouldPreferNamedOutput(currentDevice)) {
            return namedBluetoothOutput
        }

        return currentDevice
            ?: communicationDevices.firstOrNull(::isExternalHeadset)
            ?: namedBluetoothOutput
    }

    fun describeDevice(device: RelayAudioDeviceDescriptor): String {
        return listOfNotNull(
            describeDeviceType(device.type),
            device.productName?.takeIf { value -> value.isNotBlank() },
        ).joinToString(separator = " ")
    }

    fun describeDeviceType(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE speaker"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE broadcast"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            else -> "Device type $type"
        }
    }

    private fun deviceKey(device: RelayAudioDeviceDescriptor): Pair<Int, String> {
        return device.type to device.productName?.trim().orEmpty()
    }

    private fun isNamedBluetoothOutput(device: RelayAudioDeviceDescriptor): Boolean {
        return device.type in setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        ) && !device.productName.isNullOrBlank()
    }

    private fun isPlaceholderBluetoothTransport(device: RelayAudioDeviceDescriptor): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private fun shouldPreferNamedOutput(device: RelayAudioDeviceDescriptor?): Boolean {
        if (device == null) {
            return true
        }

        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || !isExternalHeadset(device)
    }

    private fun isExternalHeadset(device: RelayAudioDeviceDescriptor): Boolean {
        return device.type in setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }

    private fun devicePriority(type: Int): Int {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            -> 0

            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 1
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 2
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> 3
            else -> 4
        }
    }
}