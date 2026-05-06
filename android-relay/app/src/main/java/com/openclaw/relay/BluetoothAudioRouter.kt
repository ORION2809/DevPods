package com.openclaw.relay

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

class BluetoothAudioRouter(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun routeCommunicationAudio(): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val targetDevice = audioManager.availableCommunicationDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        } ?: return false

        return audioManager.setCommunicationDevice(targetDevice)
    }

    fun clear() {
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}