package com.openclaw.relay

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

class BluetoothAudioRouter(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun routeCommunicationAudio(): RelayAudioRouteSnapshot {
        val requestedAtMs = clock()
        if (!hasBluetoothConnectPermission()) {
            return RelayAudioRouteSnapshot(
                status = "Bluetooth permission required",
                proof = buildAudioRouteProof(
                    isActive = false,
                    isReadyForSpeechCapture = false,
                    status = "Bluetooth permission required",
                    selectedDeviceName = null,
                    selectedDeviceType = null,
                    communicationDeviceCount = 0,
                    requestedAtMs = requestedAtMs,
                    readyAtMs = clock(),
                ),
            )
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val availableDevices = audioManager.availableCommunicationDevices
        val discoveredDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val targetDevice = availableDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        } ?: return buildSnapshot(
            currentDevice = audioManager.communicationDevice,
            communicationDevices = availableDevices,
            discoveredDevices = discoveredDevices,
            routeRequestedAtMs = requestedAtMs,
            routeReadyAtMs = clock(),
        ).copy(
            isActive = false,
            isReadyForSpeechCapture = false,
            status = "No communication headset detected",
            proof = buildAudioRouteProof(
                isActive = false,
                isReadyForSpeechCapture = false,
                status = "No communication headset detected",
                selectedDeviceName = null,
                selectedDeviceType = null,
                communicationDeviceCount = availableDevices.size,
                requestedAtMs = requestedAtMs,
                readyAtMs = clock(),
            ),
        )

        val routed = audioManager.setCommunicationDevice(targetDevice)
        return buildSnapshot(
            currentDevice = audioManager.communicationDevice,
            communicationDevices = availableDevices,
            discoveredDevices = discoveredDevices,
            routeRequested = routed,
            routeRequestedAtMs = requestedAtMs,
            routeReadyAtMs = clock(),
        )
    }

    fun snapshot(): RelayAudioRouteSnapshot {
        if (!hasBluetoothConnectPermission()) {
            return RelayAudioRouteSnapshot(status = "Bluetooth permission required")
        }

        return buildSnapshot(
            currentDevice = audioManager.communicationDevice,
            communicationDevices = audioManager.availableCommunicationDevices,
            discoveredDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS),
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

    private fun buildSnapshot(
        currentDevice: AudioDeviceInfo?,
        communicationDevices: List<AudioDeviceInfo>,
        discoveredDevices: Array<AudioDeviceInfo>,
        routeRequested: Boolean? = null,
        routeRequestedAtMs: Long? = null,
        routeReadyAtMs: Long? = null,
    ): RelayAudioRouteSnapshot {
        val currentDeviceDescriptor = currentDevice?.let(::toDescriptor)
        val communicationDeviceDescriptors = communicationDevices.map(::toDescriptor)
        val discoveredDeviceDescriptors = discoveredDevices.map(::toDescriptor)
        val selectedDevice = RelayAudioDeviceCatalog.preferredSelectedDevice(
            currentDevice = currentDeviceDescriptor,
            communicationDevices = communicationDeviceDescriptors,
            discoveredDevices = discoveredDeviceDescriptors,
        )

        val status = RelayAudioDeviceCatalog.communicationRouteStatus(
            currentCommunicationDevice = currentDeviceDescriptor,
            discoveredDevices = discoveredDeviceDescriptors,
            routeRequested = routeRequested,
        )
        val selectedDeviceName = selectedDevice?.productName?.takeIf { value -> value.isNotBlank() }
        val selectedDeviceType = selectedDevice?.let { RelayAudioDeviceCatalog.describeDeviceType(it.type) }
        val isActive = routeRequested ?: (currentDeviceDescriptor != null)
        val isReadyForSpeechCapture = RelayAudioDeviceCatalog.isCommunicationCaptureDevice(currentDeviceDescriptor)

        return RelayAudioRouteSnapshot(
            isActive = isActive,
            isReadyForSpeechCapture = isReadyForSpeechCapture,
            status = status,
            selectedDeviceName = selectedDeviceName,
            selectedDeviceType = selectedDeviceType,
            communicationDeviceName = currentDeviceDescriptor?.productName?.takeIf { value -> value.isNotBlank() },
            communicationDeviceType = currentDeviceDescriptor?.let { RelayAudioDeviceCatalog.describeDeviceType(it.type) },
            availableDevices = describeDevices(communicationDeviceDescriptors, discoveredDeviceDescriptors),
            proof = buildAudioRouteProof(
                isActive = isActive,
                isReadyForSpeechCapture = isReadyForSpeechCapture,
                status = status,
                selectedDeviceName = selectedDeviceName,
                selectedDeviceType = selectedDeviceType,
                communicationDeviceCount = communicationDevices.size,
                requestedAtMs = routeRequestedAtMs,
                readyAtMs = routeReadyAtMs,
            ),
        )
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
