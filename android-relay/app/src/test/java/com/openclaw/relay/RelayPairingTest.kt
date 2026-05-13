package com.openclaw.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayPairingTest {
    @Test
    fun `parses a devpods pairing uri into relay config`() {
        val config = parseRelayPairingUri(
            "devpods://pair?bridgeBaseUrl=http%3A%2F%2F192.168.1.10%3A4545&relayToken=relay-secret&workspace=current_repo",
        )

        requireNotNull(config)
        assertEquals("http://192.168.1.10:4545", config.bridgeBaseUrl)
        assertEquals("relay-secret", config.relayToken)
        assertEquals("current_repo", config.workspace)
    }

    @Test
    fun `parses a devpods pairing uri with pairing code into a verify request`() {
        val request = parseRelayPairingRequestUri(
            "devpods://pair?bridgeBaseUrl=http%3A%2F%2F192.168.1.10%3A4545&pairingCode=ABC123&workspace=current_repo",
        )

        requireNotNull(request)
        assertEquals("http://192.168.1.10:4545", request.bridgeBaseUrl)
        assertEquals("ABC123", request.pairingCode)
        assertEquals("current_repo", request.workspace)
        assertEquals("http://192.168.1.10:4545/pairing", request.pairingPageUrl)
    }

    @Test
    fun `pairing uri defaults workspace and allows blank token`() {
        val config = parseRelayPairingUri(
            "devpods://pair?bridgeBaseUrl=https%3A%2F%2Fbridge.example.test%2Frelay",
        )

        requireNotNull(config)
        assertEquals("https://bridge.example.test/relay", config.bridgeBaseUrl)
        assertEquals("", config.relayToken)
        assertEquals("current_repo", config.workspace)
    }

    @Test
    fun `invalid pairing uri is rejected`() {
        assertNull(parseRelayPairingUri("https://bridge.example.test/relay"))
        assertNull(parseRelayPairingUri("devpods://status?bridgeBaseUrl=http%3A%2F%2F192.168.1.10%3A4545"))
        assertNull(parseRelayPairingUri("devpods://pair?relayToken=relay-secret"))
        assertNull(parseRelayPairingUri("devpods://pair?bridgeBaseUrl=not-a-url"))
    }

    @Test
    fun `bridge base url normalization accepts http and https only`() {
        assertEquals("http://192.168.1.10:4545", normalizeBridgeBaseUrl(" http://192.168.1.10:4545/ "))
        assertEquals("https://bridge.example.test/relay", normalizeBridgeBaseUrl("https://bridge.example.test/relay/"))
        assertNull(normalizeBridgeBaseUrl(""))
        assertNull(normalizeBridgeBaseUrl("not-a-url"))
        assertNull(normalizeBridgeBaseUrl("ftp://bridge.example.test/relay"))
    }

    @Test
    fun `pairing page url normalization appends pairing path when needed`() {
        assertEquals(
            "http://192.168.1.10:4545/pairing",
            normalizeRelayPairingPageUrl(" http://192.168.1.10:4545/ "),
        )
        assertEquals(
            "https://bridge.example.test/relay/pairing",
            normalizeRelayPairingPageUrl("https://bridge.example.test/relay"),
        )
        assertEquals(
            "https://bridge.example.test/relay/pairing",
            normalizeRelayPairingPageUrl("https://bridge.example.test/relay/pairing"),
        )
        assertNull(normalizeRelayPairingPageUrl("devpods://pair?bridgeBaseUrl=http%3A%2F%2F192.168.1.10%3A4545"))
    }

    @Test
    fun `parses pairing payload json into relay config`() {
        val config = parseRelayPairingPayload(
            """
            {
              "bridgeBaseUrl": "https://bridge.example.test/relay",
              "relayToken": "relay-secret",
              "workspace": "current_repo",
              "pairingUri": "devpods://pair?bridgeBaseUrl=https%3A%2F%2Fbridge.example.test%2Frelay&relayToken=relay-secret&workspace=current_repo"
            }
            """.trimIndent(),
        )

        requireNotNull(config)
        assertEquals("https://bridge.example.test/relay", config.bridgeBaseUrl)
        assertEquals("relay-secret", config.relayToken)
        assertEquals("current_repo", config.workspace)
    }

    @Test
    fun `parses pairing payload json with pairing code into verify request`() {
        val request = parseRelayPairingPayloadRequest(
            """
            {
              "bridgeBaseUrl": "https://bridge.example.test/relay",
              "pairingCode": "ABC123",
              "workspace": "current_repo",
              "pairingPageUrl": "https://bridge.example.test/relay/pairing"
            }
            """.trimIndent(),
        )

        requireNotNull(request)
        assertEquals("https://bridge.example.test/relay", request.bridgeBaseUrl)
        assertEquals("ABC123", request.pairingCode)
        assertEquals("current_repo", request.workspace)
        assertEquals("https://bridge.example.test/relay/pairing", request.pairingPageUrl)
    }
}