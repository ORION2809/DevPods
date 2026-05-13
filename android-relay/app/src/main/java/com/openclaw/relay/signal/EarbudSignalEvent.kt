package com.openclaw.relay.signal

import kotlinx.serialization.Serializable

@Serializable
sealed interface EarbudSignalEvent {
    val providerId: String
    val deviceId: String?
    val timestamp: Long

    @Serializable
    data class WakeGesture(
        override val providerId: String,
        override val deviceId: String?,
        val gestureType: GestureType,
        val budSide: BudSide? = null,
        val confidence: SignalConfidence = SignalConfidence.OBSERVED,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : EarbudSignalEvent

    @Serializable
    data class InterruptGesture(
        override val providerId: String,
        override val deviceId: String?,
        val gestureType: GestureType,
        val budSide: BudSide? = null,
        val confidence: SignalConfidence = SignalConfidence.OBSERVED,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : EarbudSignalEvent

    @Serializable
    data class ApprovalGesture(
        override val providerId: String,
        override val deviceId: String?,
        val approved: Boolean,
        val gestureType: GestureType,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : EarbudSignalEvent

    @Serializable
    data class EarStateChanged(
        override val providerId: String,
        override val deviceId: String?,
        val leftInEar: Boolean?,
        val rightInEar: Boolean?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : EarbudSignalEvent

    @Serializable
    data class BatteryChanged(
        override val providerId: String,
        override val deviceId: String?,
        val leftPercent: Int?,
        val rightPercent: Int?,
        val casePercent: Int?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : EarbudSignalEvent

    @Serializable
    data class ConnectionChanged(
        override val providerId: String,
        override val deviceId: String?,
        val connected: Boolean,
        val connectionState: String = "unknown",
        override val timestamp: Long = System.currentTimeMillis(),
    ) : EarbudSignalEvent

    @Serializable
    data class AudioRouteChanged(
        override val providerId: String,
        override val deviceId: String?,
        val routeReady: Boolean,
        val routeDescription: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : EarbudSignalEvent

    @Serializable
    data class RawDiagnosticFrame(
        override val providerId: String,
        override val deviceId: String?,
        val frameType: String,
        val payload: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : EarbudSignalEvent
}

enum class GestureType {
    SINGLE_PRESS,
    DOUBLE_PRESS,
    TRIPLE_PRESS,
    LONG_PRESS,
    UNKNOWN,
}

enum class BudSide {
    LEFT,
    RIGHT,
    BOTH,
}

enum class SignalConfidence {
    PROVEN,
    OBSERVED,
    INFERRED,
    UNPROVEN,
}
