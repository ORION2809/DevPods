package com.openclaw.relay.signal

/**
 * UI-facing representation of provider health.
 * Decoupled from the registry's internal [ProviderHealth] to avoid serialization issues.
 */
data class ProviderHealthUi(
    val providerId: String,
    val providerLabel: String,
    val status: String, // "running", "blocked_permission", "failed", "stopped"
    val deviceName: String? = null,
    val isConnected: Boolean = false,
    val lastError: String? = null,
)
