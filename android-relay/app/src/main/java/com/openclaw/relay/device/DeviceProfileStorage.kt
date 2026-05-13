package com.openclaw.relay.device

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PREFS_NAME = "devpods_device_profiles"
private const val KEY_MATRIX = "capability_matrix"
private const val KEY_CURRENT_DEVICE = "current_device_model"
private const val KEY_CURRENT_PHONE = "current_phone_model"

object DeviceProfileStorage {
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

    fun loadMatrix(context: Context): DeviceCapabilityMatrix {
        val raw = prefs(context).getString(KEY_MATRIX, null) ?: return DeviceCapabilityMatrix()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            DeviceCapabilityMatrix()
        }
    }

    fun saveMatrix(context: Context, matrix: DeviceCapabilityMatrix): Boolean {
        return commit(
            prefs(context).edit()
                .putString(KEY_MATRIX, json.encodeToString(matrix)),
        )
    }

    fun getCurrentDevice(context: Context): Pair<String, String>? {
        val prefs = prefs(context)
        val device = prefs.getString(KEY_CURRENT_DEVICE, null) ?: return null
        val phone = prefs.getString(KEY_CURRENT_PHONE, null) ?: return null
        return device to phone
    }

    fun setCurrentDevice(context: Context, deviceModel: String, phoneModel: String): Boolean {
        return commit(
            prefs(context).edit()
                .putString(KEY_CURRENT_DEVICE, deviceModel)
                .putString(KEY_CURRENT_PHONE, phoneModel),
        )
    }

    fun recordObservation(
        context: Context,
        entry: DeviceCapabilityEntry,
    ): Boolean {
        val updatedMatrix = loadMatrix(context).upsert(entry)
        return commit(
            prefs(context).edit()
                .putString(KEY_MATRIX, json.encodeToString(updatedMatrix))
                .putString(KEY_CURRENT_DEVICE, entry.deviceModel)
                .putString(KEY_CURRENT_PHONE, entry.phoneModel),
        )
    }
}
