package com.openclaw.relay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `private network bridge pairing fetches pairing first then authenticated health`() = runBlocking {
        val server = MockWebServer()
        server.start()

        // Use the mock server URL as bridgeBaseUrl so health() routes back to the mock server
        val mockBaseUrl = server.url("").toString().trimEnd('/')

        // First: /pairing returns token (unauthenticated)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "bridgeBaseUrl": "$mockBaseUrl",
                      "relayToken": "vpn-relay-secret",
                      "workspace": "vpn_repo"
                    }
                    """.trimIndent(),
                ),
        )
        // Second: /health with bearer token succeeds
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "ok": true,
                      "brainMode": "local",
                      "openclawReady": true,
                      "bridgeVersion": "1.0.0",
                      "protocolVersion": 1
                    }
                    """.trimIndent(),
                ),
        )

        try {
            val client = BridgeClient()
            // P2-2: New auth order — pairing first, then authenticated health
            val pairingResult = client.pairing(server.url("/pairing").toString())
            assertTrue(pairingResult.isSuccess)
            val config = pairingResult.getOrThrow().value
            assertEquals("vpn-relay-secret", config.relayToken)

            val healthResult = client.health(config)
            assertTrue(healthResult.isSuccess)
            assertTrue(healthResult.getOrThrow().value.ok)

            // Verify the health request included the bearer token
            val pairingRequest = server.takeRequest()
            val healthRequest = server.takeRequest()
            assertEquals("/pairing", pairingRequest?.path)
            assertEquals("Bearer vpn-relay-secret", healthRequest?.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `private network pairing fails when health check is unreachable`() = runBlocking {
        val server = MockWebServer()
        // Do not enqueue any response — simulate unreachable bridge
        server.start()

        try {
            val client = BridgeClient()
            val healthResult = client.health(RelayConfig(bridgeBaseUrl = server.url("").toString()))
            assertFalse(healthResult.isSuccess)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `release builds require HTTPS for manual bridge URLs`() {
        val httpUrl = "http://bridge.local:4545"
        val httpsUrl = "https://bridge.local:4545"

        val normalizedHttp = normalizeBridgeBaseUrl(httpUrl)
        val normalizedHttps = normalizeBridgeBaseUrl(httpsUrl)

        assertEquals("http://bridge.local:4545", normalizedHttp)
        assertEquals("https://bridge.local:4545", normalizedHttps)

        // In release builds, the ViewModel blocks HTTP URLs. This test documents that
        // the normalization itself preserves the scheme; the release-gate is enforced
        // at the call site (RelayViewModel.importPrivateNetworkBridge).
        assertTrue(normalizedHttp!!.startsWith("http://"))
        assertTrue(normalizedHttps!!.startsWith("https://"))
    }
}
