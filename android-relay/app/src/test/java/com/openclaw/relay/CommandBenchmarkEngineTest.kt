package com.openclaw.relay

import org.junit.Assert.*
import org.junit.Test

class CommandBenchmarkEngineTest {

    @Test
    fun `SHERPA_STT accepts sherpa_streaming and rejects platform IDs`() {
        assertTrue(CommandBenchmarkEngine.SHERPA_STT.acceptsRuntimeId("sherpa_streaming"))
        assertFalse(CommandBenchmarkEngine.SHERPA_STT.acceptsRuntimeId("platform_speech_recognizer"))
        assertFalse(CommandBenchmarkEngine.SHERPA_STT.acceptsRuntimeId("platform_on_device_speech_recognizer"))
    }

    @Test
    fun `PLATFORM_STT accepts platform_speech_recognizer and rejects Sherpa and on-device IDs`() {
        assertTrue(CommandBenchmarkEngine.PLATFORM_STT.acceptsRuntimeId("platform_speech_recognizer"))
        assertFalse(CommandBenchmarkEngine.PLATFORM_STT.acceptsRuntimeId("sherpa_streaming"))
        assertFalse(CommandBenchmarkEngine.PLATFORM_STT.acceptsRuntimeId("platform_on_device_speech_recognizer"))
    }

    @Test
    fun `PLATFORM_ON_DEVICE_STT accepts platform_on_device_speech_recognizer and rejects normal platform and Sherpa IDs`() {
        assertTrue(CommandBenchmarkEngine.PLATFORM_ON_DEVICE_STT.acceptsRuntimeId("platform_on_device_speech_recognizer"))
        assertFalse(CommandBenchmarkEngine.PLATFORM_ON_DEVICE_STT.acceptsRuntimeId("platform_speech_recognizer"))
        assertFalse(CommandBenchmarkEngine.PLATFORM_ON_DEVICE_STT.acceptsRuntimeId("sherpa_streaming"))
    }
}
