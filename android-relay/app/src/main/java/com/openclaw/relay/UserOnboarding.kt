package com.openclaw.relay

import android.content.Context

data class UserOnboarding(
    val hasSeenOnboarding: Boolean = false,
    val hasCompletedSetup: Boolean = false,
    val hasSkippedSetup: Boolean = false,
)

object UserOnboardingManager {
    private const val PREFS_NAME = "devpods_relay_onboarding"
    private const val KEY_HAS_SEEN_ONBOARDING = "has_seen_onboarding"
    private const val KEY_HAS_COMPLETED_SETUP = "has_completed_setup"
    private const val KEY_HAS_SKIPPED_SETUP = "has_skipped_setup"

    fun load(context: Context): UserOnboarding {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return UserOnboarding(
            hasSeenOnboarding = prefs.getBoolean(KEY_HAS_SEEN_ONBOARDING, false),
            hasCompletedSetup = prefs.getBoolean(KEY_HAS_COMPLETED_SETUP, false),
            hasSkippedSetup = prefs.getBoolean(KEY_HAS_SKIPPED_SETUP, false),
        )
    }

    fun save(context: Context, onboarding: UserOnboarding) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_HAS_SEEN_ONBOARDING, onboarding.hasSeenOnboarding)
            .putBoolean(KEY_HAS_COMPLETED_SETUP, onboarding.hasCompletedSetup)
            .putBoolean(KEY_HAS_SKIPPED_SETUP, onboarding.hasSkippedSetup)
            .apply()
    }

    fun markOnboardingSeen(context: Context) {
        save(context, load(context).copy(hasSeenOnboarding = true))
    }

    fun markSetupCompleted(context: Context) {
        save(context, load(context).copy(hasCompletedSetup = true))
    }

    fun markSetupSkipped(context: Context) {
        save(context, load(context).copy(hasSkippedSetup = true))
    }

    fun resetSetup(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_HAS_SEEN_ONBOARDING)
            .remove(KEY_HAS_COMPLETED_SETUP)
            .remove(KEY_HAS_SKIPPED_SETUP)
            .apply()
    }
}
