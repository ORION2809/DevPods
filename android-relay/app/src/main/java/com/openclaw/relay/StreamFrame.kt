package com.openclaw.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * Models for the bridge event streaming protocol (NDJSON over HTTP).
 * Each frame is a single JSON object delimited by newline.
 *
 * Use [StreamFrame.parse] to convert a JSON string into the correct frame type.
 */
sealed class StreamFrame {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(line: String): StreamFrame? {
            return try {
                val obj = json.decodeFromString<JsonObject>(line)
                val type = obj["type"]?.jsonPrimitive?.content ?: return null
                when (type) {
                    "started" -> json.decodeFromJsonElement<StreamStartedFrame>(obj)
                    "speak_delta" -> json.decodeFromJsonElement<StreamSpeakDeltaFrame>(obj)
                    "display_delta" -> json.decodeFromJsonElement<StreamDisplayDeltaFrame>(obj)
                    "approval_request" -> json.decodeFromJsonElement<StreamApprovalRequestFrame>(obj)
                    "final_response" -> json.decodeFromJsonElement<StreamFinalResponseFrame>(obj)
                    "error" -> json.decodeFromJsonElement<StreamErrorFrame>(obj)
                    "done" -> json.decodeFromJsonElement<StreamDoneFrame>(obj)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

@Serializable
data class StreamStartedFrame(val type: String = "started") : StreamFrame()

@Serializable
data class StreamSpeakDeltaFrame(val type: String = "speak_delta", val delta: String) : StreamFrame()

@Serializable
data class StreamDisplayDeltaFrame(val type: String = "display_delta", val delta: String) : StreamFrame()

@Serializable
data class StreamApprovalRequestFrame(val type: String = "approval_request", val approvalRequest: BridgeApprovalRequest) : StreamFrame()

@Serializable
data class StreamFinalResponseFrame(val type: String = "final_response", val response: BridgeJarvisResponse) : StreamFrame()

@Serializable
data class StreamErrorFrame(val type: String = "error", val error: String, val category: String? = null) : StreamFrame()

@Serializable
data class StreamDoneFrame(val type: String = "done") : StreamFrame()
