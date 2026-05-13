package com.openclaw.relay

import com.openclaw.relay.device.DeviceCapabilityMatrix
import com.openclaw.relay.device.DeviceCapabilityEntry
import com.openclaw.relay.device.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCapabilityMatrixTest {

    @Test
    fun `finds existing entry`() {
        val entry = createEntry("AirPods Pro 2", "Pixel 8")
        val matrix = DeviceCapabilityMatrix(entries = listOf(entry))
        val found = matrix.findEntry("AirPods Pro 2", "Pixel 8")
        assertNotNull(found)
        assertEquals("15", found?.androidVersion)
    }

    @Test
    fun `returns null for missing entry`() {
        val matrix = DeviceCapabilityMatrix()
        val found = matrix.findEntry("Unknown", "Unknown")
        assertNull(found)
    }

    @Test
    fun `upserts existing entry`() {
        val entry1 = createEntry("AirPods Pro 2", "Pixel 8", androidVersion = "14")
        val matrix = DeviceCapabilityMatrix(entries = listOf(entry1))
        val entry2 = createEntry("AirPods Pro 2", "Pixel 8", androidVersion = "15")
        val updated = matrix.upsert(entry2)
        assertEquals(1, updated.entries.size)
        assertEquals("15", updated.entries[0].androidVersion)
    }

    @Test
    fun `adds new entry on upsert`() {
        val entry1 = createEntry("AirPods Pro 2", "Pixel 8")
        val matrix = DeviceCapabilityMatrix(entries = listOf(entry1))
        val entry2 = createEntry("AirPods 3", "Pixel 8")
        val updated = matrix.upsert(entry2)
        assertEquals(2, updated.entries.size)
    }

    private fun createEntry(
        deviceModel: String,
        phoneModel: String,
        androidVersion: String = "15",
    ): DeviceCapabilityEntry {
        return DeviceCapabilityEntry(
            deviceModel = deviceModel,
            phoneModel = phoneModel,
            androidVersion = androidVersion,
            providersObserved = listOf("android_media_session"),
            wakeGesture = CapabilityStatus.PROVEN,
            interruptGesture = CapabilityStatus.UNPROVEN,
            approveRejectGesture = CapabilityStatus.UNPROVEN,
            earDetection = CapabilityStatus.OBSERVED,
            batteryStatus = CapabilityStatus.OBSERVED,
            sttAfterWake = CapabilityStatus.PROVEN,
            ttsInterruption = CapabilityStatus.UNPROVEN,
        )
    }
}
