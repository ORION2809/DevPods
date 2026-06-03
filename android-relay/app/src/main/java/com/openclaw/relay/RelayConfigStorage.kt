package com.openclaw.relay

import android.content.Context

object RelayConfigStorage {
    private const val PREFS_NAME = "devpods_relay_config"
    private const val KEY_BRIDGE_BASE_URL = "bridge_base_url"
    private const val KEY_RELAY_TOKEN = "relay_token"
    private const val KEY_WORKSPACE = "workspace"
    private const val KEY_USE_BLUETOOTH_ROUTING = "use_bluetooth_routing"
    private const val KEY_PHONE_MIC_FALLBACK = "phone_mic_fallback"
    private const val KEY_ASSISTANT_FALLBACK = "assistant_fallback"
    private const val KEY_SPEECH_INPUT_MODE = "speech_input_mode"
    private const val KEY_OFFLINE_SPEECH_MODEL_PATH = "offline_speech_model_path"
    private const val KEY_OFFLINE_SPEECH_MODEL_VERSION = "offline_speech_model_version"
    private const val KEY_OFFLINE_SPEECH_MODEL_SHA256 = "offline_speech_model_sha256"
    // Latency optimization feature flags
    private const val KEY_FAST_WAKE_ENABLED = "fast_wake_enabled"
    private const val KEY_TTS_WARM_KEEPALIVE_ENABLED = "tts_warm_keepalive_enabled"
    private const val KEY_SPEECH_RECOGNIZER_PREWARM_ENABLED = "speech_recognizer_prewarm_enabled"
    private const val KEY_PREFERRED_PROVIDER_ORDERING_ENABLED = "preferred_provider_ordering_enabled"
    private const val KEY_SPECULATIVE_ROUTE_PREPARE_ENABLED = "speculative_route_prepare_enabled"
    private const val KEY_BRIDGE_PREFETCH_ON_WAKE_ENABLED = "bridge_prefetch_on_wake_enabled"
    private const val KEY_EVENT_STREAMING_ENABLED = "event_streaming_enabled"
    private const val KEY_LATENCY_SUMMARY_EXPORT_ENABLED = "latency_summary_export_enabled"
    private const val KEY_REMOTE_MODE_ENABLED = "remote_mode_enabled"

    fun load(context: Context): RelayConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bridgeBaseUrl = prefs.getString(KEY_BRIDGE_BASE_URL, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return RelayConfig(
            bridgeBaseUrl = bridgeBaseUrl,
            relayToken = prefs.getString(KEY_RELAY_TOKEN, "") ?: "",
            workspace = prefs.getString(KEY_WORKSPACE, "current_repo") ?: "current_repo",
            useBluetoothRouting = prefs.getBoolean(KEY_USE_BLUETOOTH_ROUTING, true),
            phoneMicFallback = prefs.getBoolean(KEY_PHONE_MIC_FALLBACK, false),
            assistantFallback = prefs.getBoolean(KEY_ASSISTANT_FALLBACK, true),
            speechInputMode = runCatching {
                SpeechInputMode.valueOf(prefs.getString(KEY_SPEECH_INPUT_MODE, SpeechInputMode.PLATFORM.name) ?: SpeechInputMode.PLATFORM.name)
            }.getOrDefault(SpeechInputMode.PLATFORM),
            offlineSpeechModelPath = prefs.getString(KEY_OFFLINE_SPEECH_MODEL_PATH, "") ?: "",
            offlineSpeechModelVersion = prefs.getString(KEY_OFFLINE_SPEECH_MODEL_VERSION, "") ?: "",
            offlineSpeechModelSha256 = prefs.getString(KEY_OFFLINE_SPEECH_MODEL_SHA256, "") ?: "",
            // Latency optimization feature flags
            fastWakeEnabled = prefs.getBoolean(KEY_FAST_WAKE_ENABLED, false),
            ttsWarmKeepaliveEnabled = prefs.getBoolean(KEY_TTS_WARM_KEEPALIVE_ENABLED, false),
            speechRecognizerPrewarmEnabled = prefs.getBoolean(KEY_SPEECH_RECOGNIZER_PREWARM_ENABLED, false),
            preferredProviderOrderingEnabled = prefs.getBoolean(KEY_PREFERRED_PROVIDER_ORDERING_ENABLED, false),
            speculativeRoutePrepareEnabled = prefs.getBoolean(KEY_SPECULATIVE_ROUTE_PREPARE_ENABLED, false),
            bridgePrefetchOnWakeEnabled = prefs.getBoolean(KEY_BRIDGE_PREFETCH_ON_WAKE_ENABLED, false),
            eventStreamingEnabled = prefs.getBoolean(KEY_EVENT_STREAMING_ENABLED, false),
            latencySummaryExportEnabled = prefs.getBoolean(KEY_LATENCY_SUMMARY_EXPORT_ENABLED, false),
            remoteModeEnabled = prefs.getBoolean(KEY_REMOTE_MODE_ENABLED, false),
        )
    }

    fun save(context: Context, config: RelayConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_BRIDGE_BASE_URL, config.bridgeBaseUrl.trim())
            .putString(KEY_RELAY_TOKEN, config.relayToken)
            .putString(KEY_WORKSPACE, config.workspace)
            .putBoolean(KEY_USE_BLUETOOTH_ROUTING, config.useBluetoothRouting)
            .putBoolean(KEY_PHONE_MIC_FALLBACK, config.phoneMicFallback)
            .putBoolean(KEY_ASSISTANT_FALLBACK, config.assistantFallback)
            .putString(KEY_SPEECH_INPUT_MODE, config.speechInputMode.name)
            .putString(KEY_OFFLINE_SPEECH_MODEL_PATH, config.offlineSpeechModelPath)
            .putString(KEY_OFFLINE_SPEECH_MODEL_VERSION, config.offlineSpeechModelVersion)
            .putString(KEY_OFFLINE_SPEECH_MODEL_SHA256, config.offlineSpeechModelSha256)
            // Latency optimization feature flags
            .putBoolean(KEY_FAST_WAKE_ENABLED, config.fastWakeEnabled)
            .putBoolean(KEY_TTS_WARM_KEEPALIVE_ENABLED, config.ttsWarmKeepaliveEnabled)
            .putBoolean(KEY_SPEECH_RECOGNIZER_PREWARM_ENABLED, config.speechRecognizerPrewarmEnabled)
            .putBoolean(KEY_PREFERRED_PROVIDER_ORDERING_ENABLED, config.preferredProviderOrderingEnabled)
            .putBoolean(KEY_SPECULATIVE_ROUTE_PREPARE_ENABLED, config.speculativeRoutePrepareEnabled)
            .putBoolean(KEY_BRIDGE_PREFETCH_ON_WAKE_ENABLED, config.bridgePrefetchOnWakeEnabled)
            .putBoolean(KEY_EVENT_STREAMING_ENABLED, config.eventStreamingEnabled)
            .putBoolean(KEY_LATENCY_SUMMARY_EXPORT_ENABLED, config.latencySummaryExportEnabled)
            .putBoolean(KEY_REMOTE_MODE_ENABLED, config.remoteModeEnabled)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
