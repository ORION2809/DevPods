package com.openclaw.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Debug-only broadcast receiver for proof-runner automation.
 * Dispatches RUN_PROOF and EXPORT_PROOF actions to RelayService.
 * No-op in release builds (guarded by FLAG_DEBUGGABLE).
 */
class DebugProofRunnerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            Log.w(TAG, "ignored in release build")
            return
        }

        val action = intent.action ?: return
        when (action) {
            ACTION_RUN_PROOF -> {
                val serviceIntent = Intent(context, RelayService::class.java).apply {
                    this.action = RelayService.ACTION_RUN_PROOF
                    putExtra("proofTier", intent.getStringExtra("proofTier") ?: "T1_EMULATOR_SYNTHETIC")
                    putExtra("sessionCount", intent.getIntExtra("sessionCount", 20))
                    putExtra("artifactId", intent.getStringExtra("artifactId"))
                    putExtra("earbudModel", intent.getStringExtra("earbudModel"))
                    putExtra("providerId", intent.getStringExtra("providerId"))
                    putExtra("bridgeBaseUrl", intent.getStringExtra("bridgeBaseUrl"))
                    putExtra("relayToken", intent.getStringExtra("relayToken"))
                    putExtra("workspace", intent.getStringExtra("workspace") ?: "current_repo")
                }
                context.startForegroundService(serviceIntent)
                Log.i(TAG, "dispatched RUN_PROOF to RelayService")
            }
            ACTION_EXPORT_PROOF -> {
                val serviceIntent = Intent(context, RelayService::class.java).apply {
                    this.action = RelayService.ACTION_EXPORT_PROOF
                }
                context.startForegroundService(serviceIntent)
                Log.i(TAG, "dispatched EXPORT_PROOF to RelayService")
            }
        }
    }

    companion object {
        private const val TAG = "DebugProofRunnerReceiver"
        const val ACTION_RUN_PROOF = "com.openclaw.relay.action.RUN_PROOF"
        const val ACTION_EXPORT_PROOF = "com.openclaw.relay.action.EXPORT_PROOF"
    }
}
