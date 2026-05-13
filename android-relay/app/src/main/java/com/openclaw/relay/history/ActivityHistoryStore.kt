package com.openclaw.relay.history

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Privacy-preserving local activity history.
 * Stores last 20 events of each type. No bridge URLs, tokens, or workspace names.
 */
object ActivityHistoryStore {
    private const val PREFS_NAME = "devpods_activity_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 20

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun add(context: Context, entry: ActivityHistoryEntry) {
        val current = load(context).toMutableList()
        current.add(entry)
        // Keep only last MAX_ENTRIES
        val trimmed = current.takeLast(MAX_ENTRIES)
        save(context, trimmed)
    }

    fun load(context: Context): List<ActivityHistoryEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<ActivityHistoryEntry>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    private fun save(context: Context, entries: List<ActivityHistoryEntry>) {
        val raw = json.encodeToString(entries)
        prefs(context).edit().putString(KEY_ENTRIES, raw).apply()
    }
}
