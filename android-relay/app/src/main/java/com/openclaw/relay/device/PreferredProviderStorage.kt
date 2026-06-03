package com.openclaw.relay.device

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PREFS_NAME = "devpods_device_profiles"
private const val KEY_PREFERRED_PROVIDER = "preferred_provider"

@Serializable
enum class PreferredProviderSource {
    CALIBRATED,
    OBSERVED,
    MANUAL,
}

@Serializable
data class PreferredProviderRecord(
    val providerId: String,
    val source: PreferredProviderSource,
    val deviceHash: String? = null,
    val deviceModel: String? = null,
    val appVersion: String? = null,
    val androidVersion: String? = null,
    val lastSuccessAtMs: Long = System.currentTimeMillis(),
)

object PreferredProviderStorage {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun commit(editor: SharedPreferences.Editor): Boolean {
        return editor.commit()
    }

    fun load(context: Context): PreferredProviderRecord? {
        val raw = prefs(context).getString(KEY_PREFERRED_PROVIDER, null) ?: return null
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun save(context: Context, record: PreferredProviderRecord): Boolean {
        return commit(
            prefs(context).edit()
                .putString(KEY_PREFERRED_PROVIDER, json.encodeToString(record)),
        )
    }

    fun clear(context: Context): Boolean {
        return commit(
            prefs(context).edit().remove(KEY_PREFERRED_PROVIDER),
        )
    }
}
