package com.openclaw.relay.history

import kotlinx.serialization.Serializable

@Serializable
enum class ActivityEventType {
    WAKE,
    TRANSCRIPT,
    APPROVAL_REQUESTED,
    APPROVAL_APPROVED,
    APPROVAL_REJECTED,
    APPROVAL_EXPIRED,
    QUEUED,
    RETRIED,
    DISCARDED,
    SETUP_COMPLETED,
    ERROR,
}

@Serializable
data class ActivityHistoryEntry(
    val type: ActivityEventType,
    val timestampMs: Long = System.currentTimeMillis(),
    val summary: String = "",
    val detail: String = "",
)
