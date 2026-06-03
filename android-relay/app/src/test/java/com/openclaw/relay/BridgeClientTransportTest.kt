package com.openclaw.relay

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class BridgeClientTransportTest {

    @Test
    fun `sendEventStreaming receives multiple NDJSON frames in order`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(
                    """
                    {"type":"started"}
                    {"type":"speak_delta","delta":"Checking."}
                    {"type":"final_response","response":{"status":"completed","speak":"Done.","display":"Done.","requiresApproval":false,"nextState":"completed"}}
                    {"type":"done"}
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val client = BridgeClient()
            val config = RelayConfig(
                bridgeBaseUrl = server.url("").toString().trimEnd('/'),
                relayToken = "test-token",
                sessionId = "test-session",
            )
            val event = RelayBridgeEvent(
                sessionId = "test-session",
                workspace = "test_workspace",
                event = "triple_tap_right",
                timestamp = System.currentTimeMillis(),
            )

            val frames = mutableListOf<StreamFrame>()
            val result = client.sendEventStreaming(config, event) { frame ->
                frames.add(frame)
            }

            assertTrue(result.isSuccess)
            assertEquals(4, frames.size)
            assertTrue(frames[0] is StreamStartedFrame)
            assertTrue(frames[1] is StreamSpeakDeltaFrame)
            assertEquals("Checking.", (frames[1] as StreamSpeakDeltaFrame).delta)
            assertTrue(frames[2] is StreamFinalResponseFrame)
            assertTrue(frames[3] is StreamDoneFrame)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.endsWith("/events/stream"))
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `sendEventStreaming handles stream error frame`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(
                    """
                    {"type":"started"}
                    {"type":"speak_delta","delta":"Checking."}
                    {"type":"error","error":"network timeout","category":"network"}
                    {"type":"done"}
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val client = BridgeClient()
            val config = RelayConfig(
                bridgeBaseUrl = server.url("").toString().trimEnd('/'),
                relayToken = "test-token",
                sessionId = "test-session",
            )
            val event = RelayBridgeEvent(
                sessionId = "test-session",
                workspace = "test_workspace",
                event = "triple_tap_right",
                timestamp = System.currentTimeMillis(),
            )

            val frames = mutableListOf<StreamFrame>()
            val result = client.sendEventStreaming(config, event) { frame ->
                frames.add(frame)
            }

            assertTrue(result.isSuccess)
            assertTrue(frames.any { it is StreamErrorFrame })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `prefetchWorkspace parses accepted response`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"accepted":true}"""),
        )
        server.start()

        try {
            val client = BridgeClient()
            val config = RelayConfig(
                bridgeBaseUrl = server.url("").toString().trimEnd('/'),
                relayToken = "test-token",
                sessionId = "test-session",
            )

            val result = client.prefetchWorkspace(config, "test_workspace", listOf("workspace_status"))

            assertTrue("prefetch request failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
            val timedResult = result.getOrThrow()
            assertTrue("accepted was false", timedResult.value)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/prefetch"))
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("workspace_status"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `prefetchWorkspace returns false for non-2xx response`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))
        server.start()

        try {
            val client = BridgeClient()
            val config = RelayConfig(
                bridgeBaseUrl = server.url("").toString().trimEnd('/'),
                relayToken = "bad-token",
                sessionId = "test-session",
            )

            val result = client.prefetchWorkspace(config, "test_workspace")

            assertFalse(result.isSuccess)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `prefetchWorkspace fails on malformed JSON`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "prefetched": true }"""),
        )
        server.start()

        try {
            val client = BridgeClient()
            val config = RelayConfig(
                bridgeBaseUrl = server.url("").toString().trimEnd('/'),
                relayToken = "test-token",
                sessionId = "test-session",
            )

            val result = client.prefetchWorkspace(config, "test_workspace")

            assertFalse("Malformed JSON should cause failure", result.isSuccess)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `prefetchWorkspace succeeds with false for accepted=false`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"accepted":false}"""),
        )
        server.start()

        try {
            val client = BridgeClient()
            val config = RelayConfig(
                bridgeBaseUrl = server.url("").toString().trimEnd('/'),
                relayToken = "test-token",
                sessionId = "test-session",
            )

            val result = client.prefetchWorkspace(config, "test_workspace")

            assertTrue("HTTP 200 with accepted=false should still be a success Result", result.isSuccess)
            val timedResult = result.getOrThrow()
            assertFalse("accepted should be false", timedResult.value)
        } finally {
            server.shutdown()
        }
    }
}
