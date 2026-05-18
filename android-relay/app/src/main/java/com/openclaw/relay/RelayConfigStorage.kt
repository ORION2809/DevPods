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
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
