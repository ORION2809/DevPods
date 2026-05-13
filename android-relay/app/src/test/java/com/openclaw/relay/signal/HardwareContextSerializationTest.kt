package com.openclaw.relay.signal

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareContextSerializationTest {
    private val json = Json

    @Test
    fun `serializes provider field for bridge compatibility`() {
        val encoded = json.encodeToString(
            HardwareContext(
                providerId = "android_media_session",
                wakeSource = "right_single_press",
                deviceConfidence = "proven",
                earState = "both_in_ear",
                batteryState = "ok",
            )
        )

        assertTrue(encoded.contains("\"provider\":\"android_media_session\""))
        assertFalse(encoded.contains("providerId"))
    }

    @Test
    fun `decodes provider field into providerId property`() {
        val decoded = json.decodeFromString<HardwareContext>(
            """
            {
              "provider": "librepods_airpods",
              "wakeSource": "right_double_press",
              "deviceConfidence": "observed"
            }
            """.trimIndent(),
        )

        assertEquals("librepods_airpods", decoded.providerId)
        assertEquals("right_double_press", decoded.wakeSource)
        assertEquals("observed", decoded.deviceConfidence)
    }
}