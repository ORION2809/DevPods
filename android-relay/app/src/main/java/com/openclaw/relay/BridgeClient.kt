package com.openclaw.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class BridgeClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) {
    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 2L
        private const val WRITE_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val CALL_TIMEOUT_SECONDS = 20L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun health(config: RelayConfig): Result<TimedBridgeResult<BridgeHealthResponse>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/health")
                    .applyAuthorization(config)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge health request failed with ${response.code}" })
                    }

                    TimedBridgeResult(
                        value = json.decodeFromString<BridgeHealthResponse>(body),
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun sendEvent(
        config: RelayConfig,
        event: RelayBridgeEvent,
    ): Result<TimedBridgeResult<BridgeJarvisResponse>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/events")
                    .applyAuthorization(config)
                    .post(
                        json.encodeToString(event)
                            .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge event request failed with ${response.code}" })
                    }

                    TimedBridgeResult(
                        value = json.decodeFromString<BridgeJarvisResponse>(body),
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun pairing(pairingPageUrl: String): Result<TimedBridgeResult<RelayConfig>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url(pairingPageUrl)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge pairing request failed with ${response.code}" })
                    }

                    val payload = parseRelayPairingPayloadRequest(body)
                        ?: error("Bridge pairing response was invalid.")
                    val relayToken = when {
                        !payload.relayToken.isNullOrBlank() -> payload.relayToken
                        !payload.pairingCode.isNullOrBlank() -> pairingVerify(pairingPageUrl, payload.pairingCode).getOrThrow().value
                        else -> ""
                    }
                    val config = payload.toRelayConfig(relayToken)

                    TimedBridgeResult(
                        value = config,
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun pairingVerify(pairingPageUrl: String, pairingCode: String): Result<TimedBridgeResult<String>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val verifyUrl = "${pairingPageUrl.trimEnd('/')}/verify"
                val request = Request.Builder()
                    .url(verifyUrl)
                    .post(
                        json.encodeToString(mapOf("pairingCode" to pairingCode))
                            .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge pairing verify request failed with ${response.code}" })
                    }

                    val relayToken = parseRelayPairingVerifyResponse(body)
                        ?: error("Bridge pairing verify response was invalid.")

                    TimedBridgeResult(
                        value = relayToken,
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    private fun Request.Builder.applyAuthorization(config: RelayConfig): Request.Builder {
        if (config.relayToken.isBlank()) {
            return this
        }

        return header("Authorization", "Bearer ${config.relayToken}")
    }
}