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
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
