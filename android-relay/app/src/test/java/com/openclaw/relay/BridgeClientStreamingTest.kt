package com.openclaw.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@ExperimentalCoroutinesApi
class BridgeClientStreamingTest {

    @Test
    fun `StreamFrame parses speak_delta frame`() {
        val line = """{"type":"speak_delta","delta":"Checking."}"""
        val frame = StreamFrame.parse(line)
        assertNotNull(frame)
        assertTrue(frame is StreamSpeakDeltaFrame)
        assertEquals("Checking.", (frame as StreamSpeakDeltaFrame).delta)
    }

    @Test
    fun `StreamFrame parses final_response frame`() {
        val line = """{"type":"final_response","response":{"status":"completed","speak":"Done.","display":"Done.","requiresApproval":false,"nextState":"completed"}}"""
        val frame = StreamFrame.parse(line)
        assertNotNull(frame)
        assertTrue(frame is StreamFinalResponseFrame)
        assertEquals("completed", (frame as StreamFinalResponseFrame).response.status)
    }

    @Test
    fun `StreamFrame parses approval_request frame`() {
        val line = """{"type":"approval_request","approvalRequest":{"actionType":"run_tests","summary":"Run tests","riskClass":"normal","expiresInMs":30000}}"""
        val frame = StreamFrame.parse(line)
        assertNotNull(frame)
        assertTrue(frame is StreamApprovalRequestFrame)
        assertEquals("run_tests", (frame as StreamApprovalRequestFrame).approvalRequest.actionType)
    }

    @Test
    fun `StreamFrame parses error frame`() {
        val line = """{"type":"error","error":"network timeout","category":"network"}"""
        val frame = StreamFrame.parse(line)
        assertNotNull(frame)
        assertTrue(frame is StreamErrorFrame)
        assertEquals("network timeout", (frame as StreamErrorFrame).error)
    }

    @Test
    fun `StreamFrame parses done frame`() {
        val line = """{"type":"done"}"""
        val frame = StreamFrame.parse(line)
        assertNotNull(frame)
        assertTrue(frame is StreamDoneFrame)
    }

    @Test
    fun `StreamFrame drops malformed lines gracefully`() {
        val frame = StreamFrame.parse("not-json-at-all")
        assertNull(frame)
    }

    @Test
    fun `StreamFrame drops frames with missing type`() {
        val frame = StreamFrame.parse("""{"delta":"hello"}""")
        assertNull(frame)
    }
}
