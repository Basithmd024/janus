package com.janus.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts JanusService automatically when the device boots.
 * This ensures persistent connectivity without the user having to
 * manually open the app each time.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "JanusBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d(TAG, "Boot completed -- starting JanusService")

            val prefs = context.getSharedPreferences("janus_prefs", Context.MODE_PRIVATE)
            val autoStartEnabled = prefs.getBoolean("auto_start_enabled", true)

            if (!autoStartEnabled) {
                Log.d(TAG, "Auto-start is disabled by user preference, skipping")
                return
            }

            val pairedDevices = prefs.getStringSet("paired_devices", emptySet()) ?: emptySet()
            if (pairedDevices.isEmpty()) {
                Log.d(TAG, "No paired devices found, skipping auto-start")
                return
            }

            val serviceIntent = Intent(context, JanusService::class.java).apply {
                putExtra("source", "boot_receiver")
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "JanusService start requested successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start JanusService on boot", e)
            }
        }
    }
}
