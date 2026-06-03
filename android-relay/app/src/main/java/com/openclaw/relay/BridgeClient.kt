package com.openclaw.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
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

    suspend fun pollOutbox(
        config: RelayConfig,
        afterCursor: String? = null,
    ): Result<TimedBridgeResult<BridgeOutboxPollResponse>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val urlBuilder = StringBuilder("${config.bridgeBaseUrl.trimEnd('/')}/sessions/${config.sessionId}/outbox")
                if (!afterCursor.isNullOrBlank()) {
                    urlBuilder.append("?after=$afterCursor")
                }
                val request = Request.Builder()
                    .url(urlBuilder.toString())
                    .applyAuthorization(config)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge outbox poll failed with ${response.code}" })
                    }

                    TimedBridgeResult(
                        value = json.decodeFromString<BridgeOutboxPollResponse>(body),
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun ackOutboxEvent(
        config: RelayConfig,
        eventId: String,
    ): Result<TimedBridgeResult<Boolean>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/sessions/${config.sessionId}/outbox/$eventId/ack")
                    .applyAuthorization(config)
                    .post(
                        json.encodeToString(mapOf("eventId" to eventId, "sessionId" to config.sessionId))
                            .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge outbox ack failed with ${response.code}" })
                    }

                    val acked = runCatching { json.decodeFromString<Map<String, Boolean>>(body)["acked"] == true }.getOrDefault(false)
                    TimedBridgeResult(
                        value = acked,
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun getNotificationPreferences(
        config: RelayConfig,
    ): Result<TimedBridgeResult<NotificationPreference>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/sessions/${config.sessionId}/preferences/notifications")
                    .applyAuthorization(config)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge preference get failed with ${response.code}" })
                    }

                    TimedBridgeResult(
                        value = json.decodeFromString<NotificationPreference>(body),
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun setNotificationPreferences(
        config: RelayConfig,
        preference: NotificationPreference,
    ): Result<TimedBridgeResult<NotificationPreference>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/sessions/${config.sessionId}/preferences/notifications")
                    .applyAuthorization(config)
                    .post(
                        json.encodeToString(preference)
                            .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge preference set failed with ${response.code}" })
                    }

                    TimedBridgeResult(
                        value = json.decodeFromString<NotificationPreference>(body),
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun listReminders(
        config: RelayConfig,
    ): Result<TimedBridgeResult<ReminderListResponse>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/sessions/${config.sessionId}/reminders")
                    .applyAuthorization(config)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge reminder list failed with ${response.code}" })
                    }

                    TimedBridgeResult(
                        value = json.decodeFromString<ReminderListResponse>(body),
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun createReminder(
        config: RelayConfig,
        reminder: Reminder,
    ): Result<TimedBridgeResult<Reminder>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/sessions/${config.sessionId}/reminders")
                    .applyAuthorization(config)
                    .post(
                        json.encodeToString(reminder)
                            .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge reminder create failed with ${response.code}" })
                    }

                    TimedBridgeResult(
                        value = json.decodeFromString<Reminder>(body),
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun cancelReminder(
        config: RelayConfig,
        reminderId: String,
    ): Result<TimedBridgeResult<Boolean>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/sessions/${config.sessionId}/reminders/$reminderId")
                    .applyAuthorization(config)
                    .delete()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge reminder cancel failed with ${response.code}" })
                    }

                    val cancelled = runCatching { json.decodeFromString<Map<String, Boolean>>(body)["cancelled"] == true }.getOrDefault(false)
                    TimedBridgeResult(
                        value = cancelled,
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun listHabits(
        config: RelayConfig,
    ): Result<TimedBridgeResult<VoiceHabitSnapshot>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/habits")
                    .applyAuthorization(config)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge habit list failed with ${response.code}" })
                    }

                    TimedBridgeResult(
                        value = json.decodeFromString<VoiceHabitSnapshot>(body),
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun createHabit(
        config: RelayConfig,
        phrase: LearnedPhrase,
    ): Result<TimedBridgeResult<LearnedPhrase>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/habits")
                    .applyAuthorization(config)
                    .post(
                        json.encodeToString(phrase)
                            .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge habit create failed with ${response.code}" })
                    }

                    TimedBridgeResult(
                        value = json.decodeFromString<LearnedPhrase>(body),
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun deleteHabit(
        config: RelayConfig,
        phrase: String,
    ): Result<TimedBridgeResult<Boolean>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/habits/${java.net.URLEncoder.encode(phrase, "UTF-8")}")
                    .applyAuthorization(config)
                    .delete()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge habit delete failed with ${response.code}" })
                    }

                    val deleted = runCatching { json.decodeFromString<Map<String, Boolean>>(body)["deleted"] == true }.getOrDefault(false)
                    TimedBridgeResult(
                        value = deleted,
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun getNudgePolicy(
        config: RelayConfig,
    ): Result<TimedBridgeResult<NudgePolicy>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/sessions/${config.sessionId}/nudge-policy")
                    .applyAuthorization(config)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge nudge policy get failed with ${response.code}" })
                    }

                    TimedBridgeResult(
                        value = json.decodeFromString<NudgePolicy>(body),
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun setNudgePolicy(
        config: RelayConfig,
        policy: NudgePolicy,
    ): Result<TimedBridgeResult<NudgePolicy>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/sessions/${config.sessionId}/nudge-policy")
                    .applyAuthorization(config)
                    .post(
                        json.encodeToString(policy)
                            .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(body.ifBlank { "Bridge nudge policy set failed with ${response.code}" })
                    }

                    TimedBridgeResult(
                        value = json.decodeFromString<NudgePolicy>(body),
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    suspend fun sendEventStreaming(
        config: RelayConfig,
        event: RelayBridgeEvent,
        onFrame: (StreamFrame) -> Unit,
    ): Result<TimedBridgeResult<Unit>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/events/stream")
                    .applyAuthorization(config)
                    .post(
                        json.encodeToString(event)
                            .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        error(body.ifBlank { "Bridge streaming request failed with ${response.code}" })
                    }

                    val body = response.body
                        ?: error("Bridge streaming response had no body")

                    body.source().use { source ->
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val frame = StreamFrame.parse(line)
                            if (frame != null) {
                                onFrame(frame)
                            }
                        }
                    }

                    TimedBridgeResult(
                        value = Unit,
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    @Serializable
    private data class PrefetchRequestBody(
        val kinds: List<String>,
        val idempotencyKey: String? = null,
    )

    suspend fun prefetchWorkspace(
        config: RelayConfig,
        workspaceId: String,
        kinds: List<String> = listOf("workspace_status"),
        idempotencyKey: String? = null,
    ): Result<TimedBridgeResult<Boolean>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val body = PrefetchRequestBody(
                    kinds = kinds,
                    idempotencyKey = idempotencyKey,
                )
                val request = Request.Builder()
                    .url("${config.bridgeBaseUrl.trimEnd('/')}/sessions/${config.sessionId}/workspaces/${java.net.URLEncoder.encode(workspaceId, "UTF-8")}/prefetch")
                    .applyAuthorization(config)
                    .post(
                        json.encodeToString(body)
                            .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(responseBody.ifBlank { "Bridge prefetch request failed with ${response.code}" })
                    }

                    val accepted = try {
                        json.decodeFromString<PrefetchResponseBody>(responseBody).accepted
                    } catch (e: Exception) {
                        error("Prefetch response body was invalid JSON: ${e.message}")
                    }
                    TimedBridgeResult(
                        value = accepted,
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    @Serializable
    private data class PrefetchResponseBody(val accepted: Boolean)

    private fun Request.Builder.applyAuthorization(config: RelayConfig): Request.Builder {
        if (config.relayToken.isBlank()) {
            return this
        }

        return header("Authorization", "Bearer ${config.relayToken}")
    }
}