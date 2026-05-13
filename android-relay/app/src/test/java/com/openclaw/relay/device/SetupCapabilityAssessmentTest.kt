package com.openclaw.relay.device

import com.openclaw.relay.signal.EarbudSignalEvent
import com.openclaw.relay.signal.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCapabilityAssessmentTest {

    @Test
    fun `setup does not prove wake from provider availability alone`() {
        val entry = buildCapabilityEntryFromSetup(
            SetupCapabilityAssessment(
                deviceModel = null,
                phoneModel = "RMX3990",
                androidVersion = "15",
                observedEvents = emptyList(),
                detectedProviders = emptySet(),
            )
        )

        assertEquals(CapabilityStatus.UNPROVEN, entry.wakeGesture)
        assertEquals(CapabilityStatus.UNPROVEN, entry.interruptGesture)
        assertEquals(CapabilityStatus.UNPROVEN, entry.approveRejectGesture)
        assertEquals(CapabilityStatus.UNPROVEN, entry.earDetection)
        assertEquals(CapabilityStatus.UNPROVEN, entry.batteryStatus)
        assertTrue(entry.providersObserved.isEmpty())
    }

    @Test
    fun `android media session wake proves direct hardware wake`() {
        val entry = buildCapabilityEntryFromSetup(
            SetupCapabilityAssessment(
                deviceModel = "Generic Bluetooth",
                phoneModel = "RMX3990",
                androidVersion = "15",
                observedEvents = listOf(
                    EarbudSignalEvent.WakeGesture(
                        providerId = "android_media_session",
                        deviceId = null,
                        gestureType = GestureType.SINGLE_PRESS,
                    )
                ),
            )
        )

        assertEquals(CapabilityStatus.PROVEN, entry.wakeGesture)
        assertEquals(listOf("android_media_session"), entry.providersObserved)
    }

    @Test
    fun `assistant fallback wake stays observed instead of proven direct wake`() {
        val entry = buildCapabilityEntryFromSetup(
            SetupCapabilityAssessment(
                deviceModel = "Generic Bluetooth",
                phoneModel = "RMX3990",
                androidVersion = "15",
                observedEvents = listOf(
                    EarbudSignalEvent.WakeGesture(
                        providerId = "assistant_entry",
                        deviceId = null,
                        gestureType = GestureType.LONG_PRESS,
                    )
                ),
            )
        )

        assertEquals(CapabilityStatus.OBSERVED, entry.wakeGesture)
        assertEquals(listOf("assistant_entry"), entry.providersObserved)
    }

    @Test
    fun `librepods detection keeps ear and battery as observed until state changes arrive`() {
        val entry = buildCapabilityEntryFromSetup(
            SetupCapabilityAssessment(
                deviceModel = "AirPods Pro 2",
                phoneModel = "Pixel 8",
                androidVersion = "15",
                observedEvents = emptyList(),
                detectedProviders = setOf("librepods_airpods"),
            )
        )

        assertEquals("AirPods Pro 2", entry.deviceModel)
        assertEquals(CapabilityStatus.OBSERVED, entry.earDetection)
        assertEquals(CapabilityStatus.OBSERVED, entry.batteryStatus)
        assertEquals(listOf("librepods_airpods"), entry.providersObserved)
    }

    @Test
    fun `interrupt and approval require observed gestures`() {
        val entry = buildCapabilityEntryFromSetup(
            SetupCapabilityAssessment(
                deviceModel = "AirPods Pro 2",
                phoneModel = "Pixel 8",
                androidVersion = "15",
                observedEvents = listOf(
                    EarbudSignalEvent.InterruptGesture(
                        providerId = "librepods_airpods",
                        deviceId = "AA:BB",
                        gestureType = GestureType.DOUBLE_PRESS,
                    ),
                    EarbudSignalEvent.ApprovalGesture(
                        providerId = "librepods_airpods",
                        deviceId = "AA:BB",
                        approved = true,
                        gestureType = GestureType.TRIPLE_PRESS,
                    ),
                ),
                detectedProviders = setOf("librepods_airpods"),
            )
        )

        assertEquals(CapabilityStatus.UNPROVEN, entry.wakeGesture)
        assertEquals(CapabilityStatus.PROVEN, entry.interruptGesture)
        assertEquals(CapabilityStatus.PROVEN, entry.approveRejectGesture)
    }
}