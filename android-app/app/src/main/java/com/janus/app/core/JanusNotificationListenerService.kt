package com.janus.app.core

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import android.util.LruCache
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream

class JanusNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "JanusNotifListener"
        var instance: JanusNotificationListenerService? = null
            private set

        /** Packages we should never mirror to avoid loops and noise */
        private val IGNORED_PACKAGES = setOf(
            "com.janus.app",
            "android",
            "com.android.systemui",
            "com.google.android.googlequicksearchbox"
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d(TAG, "Notification listener disconnected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // MOB-08 FIX: LruCache to cache app icons and prevent garbage collection memory churn
    private val iconCache = LruCache<String, String>(50)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in IGNORED_PACKAGES) return

        // Skip ongoing/persistent notifications (media players, foreground services)
        val isOngoing = sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (isOngoing) return

        // Skip group summary notifications (we'll get the individual children)
        val isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (isGroupSummary) return

        try {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

            // Don't mirror empty notifications
            if (title.isBlank() && text.isBlank()) return

            val appName = getAppLabel(sbn.packageName)
            val appIconBase64 = getAppIconBase64(sbn.packageName)

            // Check if notification has reply action
            val actions = sbn.notification.actions
            val actionsArray = JsonArray()
            var isReplyable = false

            actions?.forEach { action ->
                val actionObj = JsonObject().apply {
                    addProperty("label", action.title?.toString() ?: "")
                    val hasRemoteInput = action.remoteInputs?.isNotEmpty() == true
                    addProperty("is_reply", hasRemoteInput)
                    if (hasRemoteInput) isReplyable = true
                }
                actionsArray.add(actionObj)
            }

            val payload = JsonObject().apply {
                addProperty("notification_id", sbn.key)
                addProperty("app_name", appName)
                addProperty("app_package", sbn.packageName)
                addProperty("title", title)
                addProperty("text", text)
                addProperty("timestamp", sbn.postTime)
                addProperty("is_replyable", isReplyable)
                if (appIconBase64 != null) {
                    addProperty("app_icon", appIconBase64)
                }
                add("actions", actionsArray)
            }

            val packet = Packet(
                type = "notification.new",
                id = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis() / 1000,
                payload = payload
            )

            // Send via JanusService's connection manager
            JanusService.instance?.connectionManager?.sendPacket(packet)

            // Send via Firebase if authenticated
            if (FirebaseManager.isAuthenticated) {
                FirebaseManager.uploadNotification(sbn.key, appName, title, text)
            }

            Log.d(TAG, "Mirrored notification: [$appName] $title — $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in IGNORED_PACKAGES) return

        try {
            val payload = JsonObject().apply {
                addProperty("notification_id", sbn.key)
                addProperty("app_package", sbn.packageName)
            }

            val packet = Packet(
                type = "notification.dismiss",
                id = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis() / 1000,
                payload = payload
            )

            JanusService.instance?.connectionManager?.sendPacket(packet)

            // Send via Firebase if authenticated
            if (FirebaseManager.isAuthenticated) {
                FirebaseManager.dismissNotification(sbn.key)
            }

            Log.d(TAG, "Notification removed: ${sbn.key}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send dismiss packet", e)
        }
    }

    // ── Actions from macOS (reply / dismiss) ─────────────────────────

    fun sendNotificationReply(notificationKey: String, replyText: String): Boolean {
        try {
            val activeNotifications = getActiveNotifications() ?: return false
            val sbn = activeNotifications.find { it.key == notificationKey } ?: run {
                Log.w(TAG, "Notification not found for reply: $notificationKey")
                return false
            }

            val actions = sbn.notification.actions ?: return false

            // Find the reply action (one with RemoteInput)
            for (action in actions) {
                val remoteInputs = action.remoteInputs
                if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                    val intent = Intent()
                    val bundle = Bundle()

                    // Fill all remote inputs with the reply text
                    for (remoteInput in remoteInputs) {
                        bundle.putCharSequence(remoteInput.resultKey, replyText)
                    }

                    RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

                    // Fire the pending intent with the filled reply
                    action.actionIntent.send(this, 0, intent)

                    Log.d(TAG, "Reply sent for $notificationKey: $replyText")
                    return true
                }
            }

            Log.w(TAG, "No reply action found for $notificationKey")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply", e)
            return false
        }
    }

    fun cancelRemoteNotification(notificationKey: String): Boolean {
        return try {
            cancelNotification(notificationKey)
            Log.d(TAG, "Cancelled notification: $notificationKey")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel notification: $notificationKey", e)
            false
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    private fun getAppIconBase64(packageName: String): String? {
        // Cache hit
        iconCache.get(packageName)?.let { return it }

        return try {
            val pm = packageManager
            val drawable = pm.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable)

            // Scale down to 48x48 to keep the WebSocket payload small
            val scaled = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 80, stream)
            val bytes = stream.toByteArray()

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            iconCache.put(packageName, base64) // Store in LruCache
            base64
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }

        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 48
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 48

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
