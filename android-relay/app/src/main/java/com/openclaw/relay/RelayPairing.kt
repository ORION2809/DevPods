package com.openclaw.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val DEV_PODS_PAIRING_SCHEME = "devpods"
private const val DEV_PODS_PAIRING_HOST = "pair"
private const val DEFAULT_WORKSPACE = "current_repo"

private val pairingJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
internal data class RelayPairingPayload(
    val bridgeBaseUrl: String,
    val relayToken: String? = null,
    val pairingCode: String? = null,
    val workspace: String? = null,
    val pairingPageUrl: String? = null,
)

@Serializable
private data class RelayPairingVerifyPayload(
    val relayToken: String,
)

internal data class RelayPairingRequest(
    val bridgeBaseUrl: String,
    val relayToken: String? = null,
    val pairingCode: String? = null,
    val workspace: String = DEFAULT_WORKSPACE,
    val pairingPageUrl: String,
) {
    fun toRelayConfig(relayTokenOverride: String? = relayToken): RelayConfig {
        return RelayConfig(
            bridgeBaseUrl = bridgeBaseUrl,
            relayToken = relayTokenOverride?.trim().orEmpty(),
            workspace = workspace,
        )
    }
}

internal fun normalizeBridgeBaseUrl(value: String): String? {
    val normalized = value.trim().trimEnd('/')
    if (normalized.isBlank()) {
        return null
    }

    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") {
        return null
    }

    if (uri.host.isNullOrBlank()) {
        return null
    }

    return normalized
}

internal fun parseRelayPairingUri(value: String): RelayConfig? {
    val request = parseRelayPairingRequestUri(value) ?: return null
    if (!request.pairingCode.isNullOrBlank() && request.relayToken.isNullOrBlank()) {
        return null
    }
    return request.toRelayConfig()
}

internal fun parseRelayPairingRequestUri(value: String): RelayPairingRequest? {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() != DEV_PODS_PAIRING_SCHEME) {
        return null
    }
    if (uri.host?.lowercase() != DEV_PODS_PAIRING_HOST) {
        return null
    }

    val params = parseQueryParams(uri.rawQuery ?: return null)
    val bridgeBaseUrl = params["bridgeBaseUrl"]
        ?.let(::normalizeBridgeBaseUrl)
        ?: return null
    val relayToken = params["relayToken"]?.trim()?.takeIf { it.isNotBlank() }
    val pairingCode = params["pairingCode"]?.trim()?.takeIf { it.isNotBlank() }
    val workspace = params["workspace"]?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_WORKSPACE
    val pairingPageUrl = normalizeRelayPairingPageUrl(bridgeBaseUrl) ?: return null

    if (relayToken == null && pairingCode == null) {
        return RelayPairingRequest(
            bridgeBaseUrl = bridgeBaseUrl,
            workspace = workspace,
            pairingPageUrl = pairingPageUrl,
        )
    }

    return RelayPairingRequest(
        bridgeBaseUrl = bridgeBaseUrl,
        relayToken = relayToken,
        pairingCode = pairingCode,
        workspace = workspace,
        pairingPageUrl = pairingPageUrl,
    )
}

internal fun normalizeRelayPairingPageUrl(value: String): String? {
    val normalized = normalizeBridgeBaseUrl(value) ?: return null
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val normalizedPath = uri.path.orEmpty().trimEnd('/').ifBlank { "" }
    val pairingPath = if (normalizedPath.lowercase().endsWith("/pairing") || normalizedPath == "/pairing") {
        normalizedPath
    } else if (normalizedPath.isBlank()) {
        "/pairing"
    } else {
        "$normalizedPath/pairing"
    }

    return URI(uri.scheme, uri.userInfo, uri.host, uri.port, pairingPath, null, null).toString()
}

internal fun parseRelayPairingPayload(value: String): RelayConfig? {
    val request = parseRelayPairingPayloadRequest(value) ?: return null
    if (!request.pairingCode.isNullOrBlank() && request.relayToken.isNullOrBlank()) {
        return null
    }
    return request.toRelayConfig()
}

internal fun parseRelayPairingPayloadRequest(value: String): RelayPairingRequest? {
    val payload = runCatching { pairingJson.decodeFromString<RelayPairingPayload>(value.trim()) }.getOrNull() ?: return null
    val bridgeBaseUrl = normalizeBridgeBaseUrl(payload.bridgeBaseUrl) ?: return null
    val relayToken = payload.relayToken?.trim()?.takeIf { it.isNotBlank() }
    val pairingCode = payload.pairingCode?.trim()?.takeIf { it.isNotBlank() }
    val workspace = payload.workspace?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_WORKSPACE
    val pairingPageUrl = payload.pairingPageUrl?.let(::normalizeRelayPairingPageUrl)
        ?: normalizeRelayPairingPageUrl(bridgeBaseUrl)
        ?: return null

    return RelayPairingRequest(
        bridgeBaseUrl = bridgeBaseUrl,
        relayToken = relayToken,
        pairingCode = pairingCode,
        workspace = workspace,
        pairingPageUrl = pairingPageUrl,
    )
}

internal fun parseRelayPairingVerifyResponse(value: String): String? {
    val payload = runCatching { pairingJson.decodeFromString<RelayPairingVerifyPayload>(value.trim()) }.getOrNull() ?: return null
    return payload.relayToken
}

private fun parseQueryParams(rawQuery: String): Map<String, String> {
    if (rawQuery.isBlank()) {
        return emptyMap()
    }

    return rawQuery.split('&')
        .mapNotNull { entry ->
            val separatorIndex = entry.indexOf('=')
            if (separatorIndex <= 0) {
                return@mapNotNull null
            }

            val key = decodeUriComponent(entry.substring(0, separatorIndex))
            val value = decodeUriComponent(entry.substring(separatorIndex + 1))
            key to value
        }
        .toMap()
}

private fun decodeUriComponent(value: String): String {
    return URLDecoder.decode(value, "UTF-8")
}