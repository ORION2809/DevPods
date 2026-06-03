package com.openclaw.relay.device

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

private const val PREFS_NAME = "devpods_device_profiles"
private const val KEY_MATRIX = "capability_matrix"
private const val KEY_CURRENT_DEVICE = "current_device_model"
private const val KEY_CURRENT_PHONE = "current_phone_model"
private const val KEY_CURRENT_DEVICE_HASH = "current_device_hash"
private const val KEY_CALIBRATION_PROFILE = "calibration_profile"
private const val KEY_CALIBRATION_HISTORY = "calibration_history"
private const val KEY_RUNTIME_MISS_COUNT = "runtime_miss_count"

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

    fun setCurrentDeviceHash(context: Context, deviceAddressHash: String): Boolean {
        return commit(
            prefs(context).edit()
                .putString(KEY_CURRENT_DEVICE_HASH, deviceAddressHash),
        )
    }

    fun getCurrentDeviceHash(context: Context): String? {
        return prefs(context).getString(KEY_CURRENT_DEVICE_HASH, null)
    }

    // --- Calibration profile storage ---

    fun loadCalibrationProfile(context: Context): com.openclaw.relay.calibration.EarbudCalibrationProfile? {
        val raw = prefs(context).getString(KEY_CALIBRATION_PROFILE, null) ?: return null
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun saveCalibrationProfile(
        context: Context,
        profile: com.openclaw.relay.calibration.EarbudCalibrationProfile,
    ): Boolean {
        return commit(
            prefs(context).edit()
                .putString(KEY_CALIBRATION_PROFILE, json.encodeToString(profile)),
        )
    }

    fun clearCalibrationProfile(context: Context): Boolean {
        return commit(
            prefs(context).edit().remove(KEY_CALIBRATION_PROFILE),
        )
    }

    /**
     * Hash a device identifier (e.g., Bluetooth MAC address) into a stable,
     * redacted string suitable for use as a storage key component.
     */
    fun hashDeviceIdentity(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun calibrationProfileKey(deviceAddressHash: String, phoneModel: String): String {
        return "${KEY_CALIBRATION_PROFILE}_${deviceAddressHash}_${phoneModel}"
    }

    fun loadDeviceCalibrationProfile(context: Context): com.openclaw.relay.calibration.EarbudCalibrationProfile? {
        val currentDevice = getCurrentDevice(context) ?: return null
        val storedHash = getCurrentDeviceHash(context)
        // Priority: stored hash (device-scoped) → hash(model) → legacy model key → legacy flat key
        val deviceScopedKey = storedHash?.let { calibrationProfileKey(it, currentDevice.second) }
        val modelHashKey = calibrationProfileKey(hashDeviceIdentity(currentDevice.first), currentDevice.second)
        val legacyKey = calibrationProfileKey(currentDevice.first, currentDevice.second)
        val raw = deviceScopedKey?.let { prefs(context).getString(it, null) }
            ?: prefs(context).getString(modelHashKey, null)
            ?: prefs(context).getString(legacyKey, null)
            ?: prefs(context).getString(KEY_CALIBRATION_PROFILE, null)
            ?: return null
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun loadDeviceCalibrationProfileByHash(
        context: Context,
        deviceAddressHash: String,
        phoneModel: String,
    ): com.openclaw.relay.calibration.EarbudCalibrationProfile? {
        val key = calibrationProfileKey(deviceAddressHash, phoneModel)
        val raw = prefs(context).getString(key, null) ?: return null
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun saveDeviceCalibrationProfile(
        context: Context,
        profile: com.openclaw.relay.calibration.EarbudCalibrationProfile,
    ): Boolean {
        val key = calibrationProfileKey(profile.deviceAddressHash, profile.phoneModel)
        return commit(
            prefs(context).edit()
                .putString(key, json.encodeToString(profile)),
        )
    }

    fun clearDeviceCalibrationProfile(context: Context): Boolean {
        val currentDevice = getCurrentDevice(context)
        val storedHash = getCurrentDeviceHash(context)
        val editor = prefs(context).edit()
        editor.remove(KEY_CALIBRATION_PROFILE)
        editor.remove(KEY_CURRENT_DEVICE_HASH)
        if (currentDevice != null) {
            storedHash?.let { editor.remove(calibrationProfileKey(it, currentDevice.second)) }
            editor.remove(calibrationProfileKey(hashDeviceIdentity(currentDevice.first), currentDevice.second))
            editor.remove(calibrationProfileKey(currentDevice.first, currentDevice.second))
        }
        return commit(editor)
    }

    fun recordCalibrationHistory(
        context: Context,
        profile: com.openclaw.relay.calibration.EarbudCalibrationProfile,
    ): Boolean {
        val history = loadCalibrationHistory(context).toMutableList()
        history.add(profile)
        // Keep last 10 entries
        if (history.size > 10) {
            history.removeAt(0)
        }
        return commit(
            prefs(context).edit()
                .putString(KEY_CALIBRATION_HISTORY, json.encodeToString(history)),
        )
    }

    fun loadCalibrationHistory(context: Context): List<com.openclaw.relay.calibration.EarbudCalibrationProfile> {
        val raw = prefs(context).getString(KEY_CALIBRATION_HISTORY, null) ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadRuntimeMissCount(context: Context): Int {
        return prefs(context).getInt(KEY_RUNTIME_MISS_COUNT, 0)
    }

    fun saveRuntimeMissCount(context: Context, count: Int): Boolean {
        return commit(
            prefs(context).edit().putInt(KEY_RUNTIME_MISS_COUNT, count),
        )
    }

    fun clearRuntimeMissCount(context: Context): Boolean {
        return commit(
            prefs(context).edit().remove(KEY_RUNTIME_MISS_COUNT),
        )
    }
}
