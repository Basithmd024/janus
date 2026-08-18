package com.janus.app.core

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.UUID

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    
    private var clipboardListenerRegistration: ListenerRegistration? = null
    private var deviceId: String = ""
    
    // Callbacks to communicate with JanusService
    var onRemoteClipboardUpdated: ((String) -> Unit)? = null
    
    fun initialize(context: Context) {
        // Generate or load a unique device ID
        val prefs = context.getSharedPreferences("janus_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        deviceId = id
        
        Log.d(TAG, "FirebaseManager initialized. Device ID: $deviceId")
        
        // Listen to Auth State changes
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                Log.d(TAG, "User signed in: ${user.email} (UID: ${user.uid})")
                startSyncing(context, user.uid)
            } else {
                Log.d(TAG, "User signed out")
                stopSyncing()
            }
        }
    }
    
    val isAuthenticated: Boolean
        get() = auth.currentUser != null
        
    val currentUserEmail: String?
        get() = auth.currentUser?.email
        
    val currentUserId: String?
        get() = auth.currentUser?.uid
        
    private fun startSyncing(context: Context, uid: String) {
        // 1. Register device status and IP in Firestore
        updateDeviceStatus(context, uid, "online")
        
        // 2. Start listening for remote clipboard changes
        startClipboardSyncListener(uid)
    }
    
    private fun stopSyncing() {
        clipboardListenerRegistration?.remove()
        clipboardListenerRegistration = null
    }
    
    fun updateDeviceStatus(context: Context, uid: String, status: String) {
        val localIp = getLocalIpAddress(context) ?: "unknown"
        val deviceData = mapOf(
            "deviceId" to deviceId,
            "name" to Build.MODEL,
            "type" to "android",
            "localIp" to localIp,
            "port" to 53318,
            "status" to status,
            "lastActive" to FieldValue.serverTimestamp()
        )
        
        db.collection("users").document(uid)
            .collection("devices").document(deviceId)
            .set(deviceData)
            .addOnSuccessListener {
                Log.d(TAG, "Device status updated to Firestore successfully: $status")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update device status: ", e)
            }
    }
    
    fun updateDeviceBattery(uid: String, batteryPct: Int, isCharging: Boolean) {
        val updates = mapOf(
            "batteryPct" to batteryPct,
            "isCharging" to isCharging,
            "lastActive" to FieldValue.serverTimestamp()
        )
        db.collection("users").document(uid)
            .collection("devices").document(deviceId)
            .update(updates)
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update device battery: ", e)
            }
    }
    
    private fun startClipboardSyncListener(uid: String) {
        clipboardListenerRegistration?.remove()
        
        clipboardListenerRegistration = db.collection("users").document(uid)
            .collection("clipboard").document("current")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Clipboard snapshot listener error: ", error)
                    return@addSnapshotListener
                }
                
                if (snapshot != null && snapshot.exists()) {
                    val senderId = snapshot.getString("senderId")
                    if (senderId != deviceId) {
                        val content = snapshot.getString("content")
                        if (content != null) {
                            Log.d(TAG, "Received clipboard content from sender: $senderId")
                            onRemoteClipboardUpdated?.invoke(content)
                        }
                    }
                }
            }
    }
    
    fun uploadClipboard(content: String) {
        val uid = currentUserId ?: return
        val clipboardData = mapOf(
            "content" to content,
            "contentType" to "text/plain",
            "senderId" to deviceId,
            "timestamp" to FieldValue.serverTimestamp()
        )
        
        db.collection("users").document(uid)
            .collection("clipboard").document("current")
            .set(clipboardData)
            .addOnSuccessListener {
                Log.d(TAG, "Local clipboard uploaded to Firestore successfully")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to upload clipboard to Firestore: ", e)
            }
    }
    
    fun uploadNotification(notificationId: String, appName: String, title: String?, text: String?) {
        val uid = currentUserId ?: return
        val notificationData = mapOf(
            "notificationId" to notificationId,
            "appName" to appName,
            "title" to (title ?: ""),
            "text" to (text ?: ""),
            "isDismissed" to false,
            "timestamp" to FieldValue.serverTimestamp()
        )
        
        db.collection("users").document(uid)
            .collection("notifications").document(notificationId)
            .set(notificationData)
            .addOnSuccessListener {
                Log.d(TAG, "Notification uploaded to Firestore: $notificationId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to upload notification: ", e)
            }
    }
    
    fun dismissNotification(notificationId: String) {
        val uid = currentUserId ?: return
        db.collection("users").document(uid)
            .collection("notifications").document(notificationId)
            .update("isDismissed", true)
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update notification dismissed state: ", e)
            }
    }
    
    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP: ", e)
            null
        }
    }
}
