package com.openclaw.relay

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayAudioDeviceCatalogTest {
    @Test
    fun `available device summary includes named bluetooth headphones from output devices`() {
        val summary = RelayAudioDeviceCatalog.availableDeviceSummary(
            communicationDevices = listOf(
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    productName = "RMX3990",
                ),
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    productName = "",
                ),
            ),
            discoveredDevices = listOf(
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    productName = "realme Buds Air7",
                ),
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    productName = "RMX3990",
                ),
            ),
        )

        assertTrue(summary.contains("Bluetooth A2DP realme Buds Air7"))
    }

    @Test
    fun `available device summary removes duplicate device labels`() {
        val summary = RelayAudioDeviceCatalog.availableDeviceSummary(
            communicationDevices = listOf(
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    productName = "RMX3990",
                ),
            ),
            discoveredDevices = listOf(
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    productName = "RMX3990",
                ),
            ),
        )

        assertEquals("Built-in speaker RMX3990", summary)
    }

    @Test
    fun `selected device prefers named bluetooth output over built in earpiece`() {
        val selected = RelayAudioDeviceCatalog.preferredSelectedDevice(
            currentDevice = RelayAudioDeviceDescriptor(
                type = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                productName = "RMX3990",
            ),
            communicationDevices = listOf(
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    productName = "RMX3990",
                ),
            ),
            discoveredDevices = listOf(
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    productName = "realme Buds Air7",
                ),
            ),
        )

        assertEquals("Bluetooth A2DP realme Buds Air7", selected?.let(RelayAudioDeviceCatalog::describeDevice))
    }

    @Test
    fun `device type labels include phone earpiece`() {
        assertEquals(
            "Phone earpiece",
            RelayAudioDeviceCatalog.describeDeviceType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
        )
    }

    @Test
    fun `speech capture readiness only accepts communication-capable headset routes`() {
        assertTrue(
            RelayAudioDeviceCatalog.isCommunicationCaptureDevice(
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    productName = "realme Buds Air7",
                ),
            ),
        )
        assertTrue(
            RelayAudioDeviceCatalog.isCommunicationCaptureDevice(
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    productName = "USB-C headset",
                ),
            ),
        )
        assertFalse(
            RelayAudioDeviceCatalog.isCommunicationCaptureDevice(
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    productName = "realme Buds Air7",
                ),
            ),
        )
        assertFalse(
            RelayAudioDeviceCatalog.isCommunicationCaptureDevice(
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    productName = "RMX3990",
                ),
            ),
        )
    }

    @Test
    fun `communication route status reports built in fallback when bluetooth output is visible`() {
        val status = RelayAudioDeviceCatalog.communicationRouteStatus(
            currentCommunicationDevice = RelayAudioDeviceDescriptor(
                type = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                productName = "RMX3990",
            ),
            discoveredDevices = listOf(
                RelayAudioDeviceDescriptor(
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    productName = "realme Buds Air7",
                ),
            ),
            routeRequested = true,
        )

        assertEquals("Headset output is visible, but the microphone route stayed on built-in audio", status)
    }
}