package com.openclaw.relay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class BridgePairingClientTest {
    @Test
    fun `bridge client imports pairing config from pairing page json`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "bridgeBaseUrl": "http://192.168.1.10:4545",
                      "relayToken": "relay-secret",
                      "workspace": "current_repo",
                      "pairingUri": "devpods://pair?bridgeBaseUrl=http%3A%2F%2F192.168.1.10%3A4545&relayToken=relay-secret&workspace=current_repo"
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val client = BridgeClient()
            val result = client.pairing(server.url("/pairing").toString())

            assertTrue(result.isSuccess)
            val payload = result.getOrThrow().value
            assertEquals("http://192.168.1.10:4545", payload.bridgeBaseUrl)
            assertEquals("relay-secret", payload.relayToken)
            assertEquals("current_repo", payload.workspace)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `bridge client exchanges pairing code before returning relay config`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "bridgeBaseUrl": "http://192.168.1.10:4545",
                      "pairingCode": "ABC123",
                      "workspace": "current_repo",
                      "pairingPageUrl": "http://192.168.1.10:4545/pairing"
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "relayToken": "relay-secret"
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val client = BridgeClient()
            val result = client.pairing(server.url("/pairing").toString())

            assertTrue(result.isSuccess)
            val payload = result.getOrThrow().value
            assertEquals("http://192.168.1.10:4545", payload.bridgeBaseUrl)
            assertEquals("relay-secret", payload.relayToken)
            assertEquals("current_repo", payload.workspace)

            val pairingRequest = server.takeRequest()
            val verifyRequest = server.takeRequest()
            assertEquals("/pairing", pairingRequest.path)
            assertEquals("/pairing/verify", verifyRequest.path)
            assertEquals("POST", verifyRequest.method)
            assertTrue(verifyRequest.body.readUtf8().contains("ABC123"))
        } finally {
            server.shutdown()
        }
    }
}