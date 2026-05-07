package com.openclaw.relay

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

class BluetoothAudioRouter(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun routeCommunicationAudio(): RelayAudioRouteSnapshot {
        if (!hasBluetoothConnectPermission()) {
            return RelayAudioRouteSnapshot(status = "Bluetooth permission required")
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val availableDevices = audioManager.availableCommunicationDevices
        val discoveredDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val availableDeviceDescriptors = availableDevices.map(::toDescriptor)
        val discoveredDeviceDescriptors = discoveredDevices.map(::toDescriptor)
        val targetDevice = availableDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        } ?: return RelayAudioRouteSnapshot(
            status = "No communication headset detected",
            availableDevices = describeDevices(availableDeviceDescriptors, discoveredDeviceDescriptors),
        )

        val routed = audioManager.setCommunicationDevice(targetDevice)
        val selectedDevice = RelayAudioDeviceCatalog.preferredSelectedDevice(
            currentDevice = toDescriptor(targetDevice),
            communicationDevices = availableDeviceDescriptors,
            discoveredDevices = discoveredDeviceDescriptors,
        )
        return RelayAudioRouteSnapshot(
            isActive = routed,
            status = if (routed) "Routed for communication" else "Route request failed",
            selectedDeviceName = selectedDevice?.productName?.takeIf { value -> value.isNotBlank() },
            selectedDeviceType = selectedDevice?.let { RelayAudioDeviceCatalog.describeDeviceType(it.type) },
            availableDevices = describeDevices(availableDeviceDescriptors, discoveredDeviceDescriptors),
        )
    }

    fun snapshot(): RelayAudioRouteSnapshot {
        if (!hasBluetoothConnectPermission()) {
            return RelayAudioRouteSnapshot(status = "Bluetooth permission required")
        }

        val currentDevice = audioManager.communicationDevice
        val communicationDevices = audioManager.availableCommunicationDevices
        val discoveredDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val currentDeviceDescriptor = currentDevice?.let(::toDescriptor)
        val communicationDeviceDescriptors = communicationDevices.map(::toDescriptor)
        val discoveredDeviceDescriptors = discoveredDevices.map(::toDescriptor)
        val selectedDevice = RelayAudioDeviceCatalog.preferredSelectedDevice(
            currentDevice = currentDeviceDescriptor,
            communicationDevices = communicationDeviceDescriptors,
            discoveredDevices = discoveredDeviceDescriptors,
        )
        return RelayAudioRouteSnapshot(
            isActive = selectedDevice != null,
            status = if (selectedDevice != null) "Communication device selected" else "No active communication route",
            selectedDeviceName = selectedDevice?.productName?.takeIf { value -> value.isNotBlank() },
            selectedDeviceType = selectedDevice?.let { RelayAudioDeviceCatalog.describeDeviceType(it.type) },
            availableDevices = describeDevices(communicationDeviceDescriptors, discoveredDeviceDescriptors),
        )
    }

    fun clear() {
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun describeDevices(
        communicationDevices: List<RelayAudioDeviceDescriptor>,
        discoveredDevices: List<RelayAudioDeviceDescriptor>,
    ): String {
        return RelayAudioDeviceCatalog.availableDeviceSummary(
            communicationDevices = communicationDevices,
            discoveredDevices = discoveredDevices,
        )
    }

    private fun toDescriptor(device: AudioDeviceInfo): RelayAudioDeviceDescriptor {
        return RelayAudioDeviceDescriptor(
            type = device.type,
            productName = device.productName?.toString()?.takeIf { value -> value.isNotBlank() },
        )
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}