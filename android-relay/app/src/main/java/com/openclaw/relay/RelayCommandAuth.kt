package com.openclaw.relay

import android.content.Context
import android.content.Intent
import java.util.UUID

internal const val EXTRA_CALLER_TOKEN = "callerToken"

private const val RELAY_COMMAND_PREFS = "relay-command-auth"
private const val RELAY_COMMAND_TOKEN_KEY = "relay-command-token"

internal fun relayCommandRequiresAuth(action: String?): Boolean {
    return action in setOf(
        RelayService.ACTION_START_RELAY,
        RelayService.ACTION_STOP_RELAY,
        RelayService.ACTION_CHECK_HEALTH,
        RelayService.ACTION_WAKE_AND_LISTEN,
        RelayService.ACTION_QUICK_STATUS,
        RelayService.ACTION_ASSIST_LONG_PRESS,
        RelayService.ACTION_TEST_SPEAKER,
        RelayService.ACTION_TAP_TEST,
        RelayService.ACTION_APPROVE,
        RelayService.ACTION_REJECT,
        RelayService.ACTION_CANCEL,
        RelayService.ACTION_RETRY_QUEUE,
        RelayService.ACTION_DISCARD_QUEUE,
        RelayService.ACTION_DEBUG_EVENT,
    )
}

internal fun isAuthorizedRelayCommand(
    action: String?,
    providedToken: String?,
    expectedToken: String?,
): Boolean {
    if (!relayCommandRequiresAuth(action)) {
        return true
    }

    return !providedToken.isNullOrBlank() && providedToken == expectedToken
}

internal fun buildRelayServiceIntent(context: Context, action: String): Intent {
    return Intent(context, RelayService::class.java)
        .setAction(action)
        .putExtra(EXTRA_CALLER_TOKEN, relayCommandToken(context))
}

internal fun hasValidRelayCommandToken(context: Context, intent: Intent?): Boolean {
    return isAuthorizedRelayCommand(
        action = intent?.action,
        providedToken = intent?.getStringExtra(EXTRA_CALLER_TOKEN),
        expectedToken = relayCommandToken(context),
    )
}

private fun relayCommandToken(context: Context): String {
    val preferences = context.getSharedPreferences(RELAY_COMMAND_PREFS, Context.MODE_PRIVATE)
    val existingToken = preferences.getString(RELAY_COMMAND_TOKEN_KEY, null)
    if (!existingToken.isNullOrBlank()) {
        return existingToken
    }

    val newToken = UUID.randomUUID().toString()
    preferences.edit().putString(RELAY_COMMAND_TOKEN_KEY, newToken).apply()
    return newToken
}
