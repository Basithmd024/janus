package com.janus.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.JsonObject

class JanusService : Service() {
    companion object {
        /** Static reference so NotificationListenerService can access the connection. */
        var instance: JanusService? = null
            private set
    }

    private val binder = LocalBinder()
    private var isServiceRunning = false

    lateinit var identity: Identity
        private set
        
    var discoveryManager: DiscoveryManager? = null
        private set
        
    var connectionManager: ConnectionManager? = null
        private set

    var audioBridge: JanusAudioBridge? = null
        private set

    var httpServerManager: HttpServerManager? = null
        private set

    var isConnected = false
        private set

    // Clipboard sync fields
    private var clipboardManager: android.content.ClipboardManager? = null
    private var clipboardListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null
    /** Hash of the last content we RECEIVED from remote — used to suppress echo loops */
    private var lastRemoteClipHash: Int = 0
    /** Hash of the last content we SENT to remote — used to suppress duplicate sends */
    private var lastSentClipHash: Int = 0

    // Telemetry fields
    private var currentBatteryLevel = -1
    private var currentIsCharging = false
    private var currentSignalLevel = -1

    // Periodic telemetry timer
    private var telemetryHandler: android.os.Handler? = null
    private var telemetryRunnable: Runnable? = null
    
    private var phoneStateListener: android.telephony.PhoneStateListener? = null
    private var telephonyCallback: android.telephony.TelephonyCallback? = null

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == android.os.BatteryManager.BATTERY_STATUS_FULL
                val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
                currentBatteryLevel = batteryPct
                currentIsCharging = isCharging
                sendTelemetryUpdate()
            }
        }
    }

    // Cached discovered devices so UI binding never misses previously resolved nodes
    val discoveredServices = java.util.concurrent.ConcurrentHashMap<String, NsdServiceInfo>()

    // Observable states via callbacks (simplified for MVP, MainActivity will bind and register)
    var onDeviceDiscovered: ((NsdServiceInfo) -> Unit)? = null
    var onDeviceRemoved: ((NsdServiceInfo) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onPacketReceived: ((Packet) -> Unit)? = null
    var onUploadProgress: ((sessionId: String, fileHash: String, bytesReceived: Long, totalBytes: Long, name: String) -> Unit)? = null
    var onUploadComplete: ((sessionId: String, fileHash: String, fileName: String, uri: android.net.Uri?) -> Unit)? = null
    var onScreenMirrorRequest: (() -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): JanusService = this@JanusService
    }

    private val phoneStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.intent.action.PHONE_STATE") {
                val stateStr = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)
                val number = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER)
                
                Log.d("JanusService", "Phone state changed: $stateStr, number: $number")
                
                val state = when (stateStr) {
                    android.telephony.TelephonyManager.EXTRA_STATE_RINGING -> android.telephony.TelephonyManager.CALL_STATE_RINGING
                    android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
                    else -> android.telephony.TelephonyManager.CALL_STATE_IDLE
                }
                
                handleCallState(state, number)
            }
        }
    }

    private fun handleCallState(state: Int, number: String?) {
        val payload = JsonObject()
        
        when (state) {
            android.telephony.TelephonyManager.CALL_STATE_RINGING -> {
                val phoneNumber = number ?: "Unknown Number"
                val resolvedName = resolveContactName(this, phoneNumber)
                
                payload.addProperty("phone_number", phoneNumber)
                payload.addProperty("caller_name", resolvedName)
                payload.addProperty("timestamp", System.currentTimeMillis() / 1000)
                
                val packet = Packet(
                    type = "call.incoming",
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis() / 1000,
                    payload = payload
                )
                connectionManager?.sendPacket(packet)
                audioBridge?.start()
            }
            android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> {
                payload.addProperty("state", "offhook")
                val packet = Packet(
                    type = "call.state",
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis() / 1000,
                    payload = payload
                )
                connectionManager?.sendPacket(packet)
                audioBridge?.start()
            }
            android.telephony.TelephonyManager.CALL_STATE_IDLE -> {
                payload.addProperty("state", "idle")
                val packet = Packet(
                    type = "call.state",
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis() / 1000,
                    payload = payload
                )
                connectionManager?.sendPacket(packet)
                audioBridge?.stop()
            }
        }
    }

    private fun resolveContactName(context: Context, phoneNumber: String): String {
        val uri = android.net.Uri.withAppendedPath(
            android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(phoneNumber)
        )
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (index != -1) return cursor.getString(index)
                }
            }
        } catch (e: Exception) {
            Log.w("JanusService", "Could not resolve contact name for $phoneNumber", e)
        }
        return phoneNumber
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("JanusService", "Service onCreate")
        instance = this
        identity = IdentityManager.getOrGenerateIdentity(this)

        // Initialize Firebase
        FirebaseManager.initialize(this)
        FirebaseManager.onRemoteClipboardUpdated = { text ->
            writeRemoteClipboardToSystem(text)
        }

        // Register phone state receiver
        val filter = android.content.IntentFilter("android.intent.action.PHONE_STATE")
        registerReceiver(phoneStateReceiver, filter)

        // Register battery receiver
        val batteryFilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, batteryFilter)

        // Read initial battery level immediately (sticky broadcast)
        readInitialBattery()

        // Register signal strength listener
        setupSignalStrengthListener()

        // Read initial signal level immediately
        readInitialSignal()

        Log.d("JanusService", "📡 Initial telemetry: battery=$currentBatteryLevel%, charging=$currentIsCharging, signal=$currentSignalLevel")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("JanusService", "Service onStartCommand")
        if (!isServiceRunning) {
            startForegroundServiceNotification()
            isServiceRunning = true
            
            // Initialize connection manager
            connectionManager = ConnectionManager(
                context = this,
                identity = identity,
                onConnectionStateChanged = { connected ->
                    isConnected = connected
                    onConnectionStateChanged?.invoke(connected)
                    if (connected) {
                        // Re-read latest values before sending
                        readInitialBattery()
                        readInitialSignal()
                        sendTelemetryUpdate()
                        startClipboardMonitoring()
                        startPeriodicTelemetry()
                        syncCalls()
                        syncSms()
                    } else {
                        stopClipboardMonitoring()
                        stopPeriodicTelemetry()
                    }
                },
                onPacketReceived = { packet: Packet ->
                    onPacketReceived?.invoke(packet)
                    handleIncomingPacket(packet)
                }
            )
            connectionManager?.onBinaryReceived = { bytes ->
                if (bytes.isNotEmpty() && bytes[0] == 0x03.toByte()) {
                    val audioBytes = ByteArray(bytes.size - 1)
                    System.arraycopy(bytes, 1, audioBytes, 0, audioBytes.size)
                    audioBridge?.writeAudio(audioBytes)
                }
            }
            audioBridge = JanusAudioBridge(this)

            // Initialize HTTP server manager for receiving files
            httpServerManager = HttpServerManager(
                context = this,
                identity = identity,
                onProgress = { sessionId, fileHash, bytesReceived, totalBytes, name ->
                    onUploadProgress?.invoke(sessionId, fileHash, bytesReceived, totalBytes, name)
                },
                onComplete = { sessionId, fileHash, fileName, uri ->
                    onUploadComplete?.invoke(sessionId, fileHash, fileName, uri)
                }
            ).apply {
                start(53318)
            }

            // Initialize discovery manager
            discoveryManager = DiscoveryManager(
                context = this,
                onDeviceFound = { nsdInfo ->
                    discoveredServices[nsdInfo.serviceName] = nsdInfo
                    onDeviceDiscovered?.invoke(nsdInfo)

                    // Auto-reconnect if it's a paired device and we are not currently connected
                    val fnBytes = nsdInfo.attributes?.get("fn")
                    val fingerprint = if (fnBytes != null) String(fnBytes, Charsets.UTF_8) else null
                    if (fingerprint != null && connectionManager?.isFingerprintPaired(fingerprint) == true) {
                        if (!isConnected) {
                            val txtIp = nsdInfo.attributes?.get("ip")?.let { String(it) }
                            val ip = txtIp ?: nsdInfo.host?.hostAddress
                            if (ip != null) {
                                Log.d("JanusService", "Auto-reconnecting to paired device: ${nsdInfo.serviceName} at $ip")
                                connectionManager?.connectToDevice(ip, nsdInfo.port, fingerprint)
                            }
                        }
                    }
                },
                onDeviceRemoved = { nsdInfo ->
                    discoveredServices.remove(nsdInfo.serviceName)
                    onDeviceRemoved?.invoke(nsdInfo)
                }
            )

            // Start advertising our existence so Mac can see us
            discoveryManager?.startAdvertising(Build.MODEL, 53318, identity.fingerprint)
            discoveryManager?.startBrowsing()

            // ⚡ Instant Fast-path Auto-connect to saved hosts
            connectionManager?.autoConnectToSavedHosts()

            // Register network change listener for zero-interaction auto-connect on Wi-Fi/Hotspot attach
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val networkRequest = android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm?.registerNetworkCallback(networkRequest, object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        Log.d("JanusService", "🌐 Network available — triggering background auto-connect")
                        connectionManager?.autoConnectToSavedHosts()
                    }
                })
            } catch (e: Exception) {
                Log.w("JanusService", "Could not register network callback", e)
            }
        }
        return START_STICKY
    }

    private fun handleIncomingPacket(packet: Packet) {
        when (packet.type) {
            "device.request_status" -> {
                readInitialBattery()
                readInitialSignal()
                sendTelemetryUpdate()
            }
            "clipboard.update" -> {
                val text = packet.payload.get("content")?.asString ?: return
                Log.d("JanusService", "Received remote clipboard update: ${text.take(50)}")
                
                // Mark as remote content to prevent echo loop
                lastRemoteClipHash = text.hashCode()
                
                // Update Android system clipboard
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Janus Clipboard", text)
                clipboard.setPrimaryClip(clip)
            }
            "notification.action" -> {
                val notificationId = packet.payload.get("notification_id")?.asString ?: return
                val action = packet.payload.get("action")?.asString ?: return
                
                val listener = JanusNotificationListenerService.instance
                if (listener == null) {
                    Log.w("JanusService", "NotificationListenerService not active — cannot process action")
                    return
                }

                when (action) {
                    "reply" -> {
                        val replyText = packet.payload.get("reply_text")?.asString ?: return
                        val success = listener.sendNotificationReply(notificationId, replyText)
                        Log.d("JanusService", "Notification reply ${if (success) "sent" else "failed"}: $notificationId")
                    }
                    "dismiss" -> {
                        val success = listener.cancelRemoteNotification(notificationId)
                        Log.d("JanusService", "Notification dismiss ${if (success) "sent" else "failed"}: $notificationId")
                    }
                }
            }
            "call.action" -> {
                val action = packet.payload.get("action")?.asString ?: return
                when (action) {
                    "dial" -> {
                        val number = packet.payload.get("phone_number")?.asString ?: return
                        dialPhoneNumber(number)
                    }
                    "answer" -> {
                        answerRingingCall()
                    }
                    "hangup" -> {
                        hangupActiveCall()
                    }
                }
            }
            "screencast.action" -> {
                val action = packet.payload.get("action")?.asString ?: return
                if (action == "start") {
                    onScreenMirrorRequest?.invoke()
                } else if (action == "stop") {
                    stopScreenMirroring()
                }
            }
            "sync.calls" -> {
                syncCalls()
            }
            "sync.sms" -> {
                syncSms()
            }
            "input.action" -> {
                val action = packet.payload.get("action")?.asString ?: return
                when (action) {
                    "click" -> {
                        val x = packet.payload.get("x")?.asFloat ?: return
                        val y = packet.payload.get("y")?.asFloat ?: return
                        injectRemoteClick(x, y)
                    }
                    "swipe" -> {
                        val startX = packet.payload.get("startX")?.asFloat ?: return
                        val startY = packet.payload.get("startY")?.asFloat ?: return
                        val endX = packet.payload.get("endX")?.asFloat ?: return
                        val endY = packet.payload.get("endY")?.asFloat ?: return
                        val duration = packet.payload.get("duration")?.asLong ?: 300L
                        injectRemoteSwipe(startX, startY, endX, endY, duration)
                    }
                    "key" -> {
                        val key = packet.payload.get("key")?.asString ?: return
                        injectRemoteKey(key)
                    }
                }
            }
        }
    }

    private fun injectRemoteClick(xRatio: Float, yRatio: Float) {
        val accessibilityService = JanusAccessibilityService.instance
        if (accessibilityService == null) {
            Log.w("JanusService", "Accessibility Service is not active. Cannot inject click.")
            return
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val displayMetrics = android.util.DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            displayMetrics.widthPixels = bounds.width()
            displayMetrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
        }

        val x = xRatio * displayMetrics.widthPixels
        val y = yRatio * displayMetrics.heightPixels

        accessibilityService.injectClick(x, y)
    }

    private fun injectRemoteSwipe(startXRatio: Float, startYRatio: Float, endXRatio: Float, endYRatio: Float, duration: Long) {
        val accessibilityService = JanusAccessibilityService.instance
        if (accessibilityService == null) {
            Log.w("JanusService", "Accessibility Service is not active. Cannot inject swipe.")
            return
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val displayMetrics = android.util.DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            displayMetrics.widthPixels = bounds.width()
            displayMetrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
        }

        val startX = startXRatio * displayMetrics.widthPixels
        val startY = startYRatio * displayMetrics.heightPixels
        val endX = endXRatio * displayMetrics.widthPixels
        val endY = endYRatio * displayMetrics.heightPixels

        accessibilityService.injectSwipe(startX, startY, endX, endY, duration)
    }

    private fun injectRemoteKey(key: String) {
        val accessibilityService = JanusAccessibilityService.instance
        if (accessibilityService == null) {
            Log.w("JanusService", "Accessibility Service is not active. Cannot inject key.")
            return
        }

        val action = when (key.lowercase()) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            else -> return
        }

        accessibilityService.performSystemAction(action)
    }

    private fun dialPhoneNumber(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startActivity(intent)
            } else {
                Log.w("JanusService", "CALL_PHONE permission not granted")
            }
        } catch (e: Exception) {
            Log.e("JanusService", "Failed to place call", e)
        }
    }

    private fun answerRingingCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    @Suppress("DEPRECATION")
                    telecomManager.acceptRingingCall()
                    Log.d("JanusService", "Ringing call answered via TelecomManager")
                } catch (e: Exception) {
                    Log.e("JanusService", "Error answering call", e)
                }
            } else {
                Log.w("JanusService", "ANSWER_PHONE_CALLS permission not granted")
            }
        }
    }

    private fun hangupActiveCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    @Suppress("DEPRECATION")
                    telecomManager.endCall()
                    Log.d("JanusService", "Active call ended via TelecomManager")
                } catch (e: Exception) {
                    Log.e("JanusService", "Error ending call", e)
                }
            } else {
                Log.w("JanusService", "ANSWER_PHONE_CALLS permission not granted")
            }
        }
    }

    private fun stopScreenMirroring() {
        val stopIntent = Intent(this, JanusScreenCastService::class.java)
        stopService(stopIntent)
        Log.d("JanusService", "Screen mirroring service stopped via remote command")
    }

    private fun startForegroundServiceNotification() {
        val channelId = "janus_connection_channel"
        val channelName = "Janus Connection Channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val syncIntent = Intent(this, com.janus.app.ClipboardSyncActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            syncIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Janus Ecosystem Bridge")
            .setContentText("Connected and syncing with Mac")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_send, "Sync Clipboard", pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("JanusService", "Service onDestroy")
        instance = null

        audioBridge?.stop()
        audioBridge = null

        // Unregister phone state receiver
        try {
            unregisterReceiver(phoneStateReceiver)
        } catch (e: Exception) {
            Log.e("JanusService", "Error unregistering phoneStateReceiver", e)
        }

        // Unregister battery receiver
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.e("JanusService", "Error unregistering batteryReceiver", e)
        }

        // Cleanup signal listener
        cleanupSignalStrengthListener()

        stopPeriodicTelemetry()
        stopClipboardMonitoring()
        discoveryManager?.stopAdvertising()
        discoveryManager?.stopBrowsing()
        httpServerManager?.stop()
        connectionManager?.disconnect()
        isServiceRunning = false
    }

    private fun sendTelemetryUpdate() {
        // If values haven't been read yet, try reading now
        if (currentBatteryLevel < 0) readInitialBattery()
        if (currentSignalLevel < 0) readInitialSignal()

        // Clamp to valid ranges
        val batteryToSend = if (currentBatteryLevel < 0) 100 else currentBatteryLevel
        val signalToSend = if (currentSignalLevel < 0) 4 else currentSignalLevel

        FirebaseManager.currentUserId?.let { uid ->
            FirebaseManager.updateDeviceBattery(uid, batteryToSend, currentIsCharging)
        }
        if (!isConnected) return

        Log.d("JanusService", "📡 Sending telemetry: battery=${batteryToSend}%, charging=$currentIsCharging, signal=$signalToSend")

        val payload = JsonObject().apply {
            addProperty("battery_level", batteryToSend)
            addProperty("is_charging", currentIsCharging)
            addProperty("signal_level", signalToSend)
        }
        val packet = Packet(
            type = "device.status",
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis() / 1000,
            payload = payload
        )
        connectionManager?.sendPacket(packet)
    }

    /** Read current real hardware battery capacity */
    private fun readInitialBattery() {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            if (bm != null) {
                val cap = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (cap in 0..100) {
                    currentBatteryLevel = cap
                }
                val status = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
                currentIsCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
            }

            val batteryStatus = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryStatus != null) {
                val level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                val status = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                if (status != -1) {
                    currentIsCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                        status == android.os.BatteryManager.BATTERY_STATUS_FULL
                }
                if (currentBatteryLevel < 0 && level >= 0 && scale > 0) {
                    currentBatteryLevel = (level * 100) / scale
                }
            }
            Log.d("JanusService", "🔋 Read real-time battery: $currentBatteryLevel%, charging=$currentIsCharging")
        } catch (e: Exception) {
            Log.e("JanusService", "Failed to read initial battery", e)
        }
    }

    /** Read current signal strength immediately */
    private fun readInitialSignal() {
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signalStrength = telephonyManager.signalStrength
                if (signalStrength != null) {
                    currentSignalLevel = signalStrength.level  // 0 to 4
                    Log.d("JanusService", "📶 Read initial signal: $currentSignalLevel")
                }
            }
        } catch (e: Exception) {
            Log.e("JanusService", "Failed to read initial signal", e)
            currentSignalLevel = 4  // Fallback to full bars
        }
    }

    /** Start sending telemetry updates every 30 seconds */
    private fun startPeriodicTelemetry() {
        stopPeriodicTelemetry()
        telemetryHandler = android.os.Handler(android.os.Looper.getMainLooper())
        telemetryRunnable = object : Runnable {
            override fun run() {
                if (isConnected) {
                    readInitialBattery()  // Re-read fresh values
                    sendTelemetryUpdate()
                    telemetryHandler?.postDelayed(this, 30_000)  // Every 30 seconds
                }
            }
        }
        telemetryHandler?.postDelayed(telemetryRunnable!!, 30_000)
        Log.d("JanusService", "📡 Periodic telemetry started (30s interval)")
    }

    /** Stop periodic telemetry updates */
    private fun stopPeriodicTelemetry() {
        telemetryRunnable?.let { telemetryHandler?.removeCallbacks(it) }
        telemetryHandler = null
        telemetryRunnable = null
    }

    // ──────────────────────────────────────────────
    // Clipboard Monitoring — Live sync Android → Mac
    // ──────────────────────────────────────────────

    fun writeRemoteClipboardToSystem(text: String) {
        lastRemoteClipHash = text.hashCode()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Janus Clipboard", text)
        clipboard.setPrimaryClip(clip)
    }

    fun sendClipboard(text: String) {
        if (text.isEmpty()) return
        val contentHash = text.hashCode()
        lastSentClipHash = contentHash

        if (FirebaseManager.isAuthenticated) {
            FirebaseManager.uploadClipboard(text)
        }

        val payload = JsonObject().apply {
            addProperty("content", text)
            addProperty("contentType", "text/plain")
        }
        val packet = Packet(
            type = "clipboard.update",
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis() / 1000,
            payload = payload
        )
        connectionManager?.sendPacket(packet)
    }

    private fun startClipboardMonitoring() {
        if (clipboardListener != null) return // Already running

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        clipboardListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            val clip = clipboardManager?.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount == 0) return@OnPrimaryClipChangedListener

            val text = clip.getItemAt(0)?.text?.toString()
            if (text.isNullOrEmpty()) return@OnPrimaryClipChangedListener

            val contentHash = text.hashCode()

            // Echo-loop prevention: skip if this is content we just received from remote
            if (contentHash == lastRemoteClipHash) {
                Log.d("JanusService", "Clipboard echo suppressed (remote content)")
                return@OnPrimaryClipChangedListener
            }

            // Duplicate prevention: skip if we already sent this exact content
            if (contentHash == lastSentClipHash) {
                return@OnPrimaryClipChangedListener
            }

            // Check for sensitive content (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val desc = clip.description
                if (desc?.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, false) == true) {
                    Log.d("JanusService", "Clipboard contains sensitive content — not syncing")
                    return@OnPrimaryClipChangedListener
                }
            }

            lastSentClipHash = contentHash

            Log.d("JanusService", "📋 Local clipboard changed, sending to Mac: ${text.take(50)}")

            // Upload to Firebase if logged in
            if (FirebaseManager.isAuthenticated) {
                FirebaseManager.uploadClipboard(text)
            }

            val payload = JsonObject().apply {
                addProperty("content", text)
                addProperty("contentType", "text/plain")
            }
            val packet = Packet(
                type = "clipboard.update",
                id = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis() / 1000,
                payload = payload
            )
            connectionManager?.sendPacket(packet)
        }

        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        Log.d("JanusService", "📋 Clipboard monitoring started")
    }

    private fun stopClipboardMonitoring() {
        clipboardListener?.let {
            clipboardManager?.removePrimaryClipChangedListener(it)
            Log.d("JanusService", "📋 Clipboard monitoring stopped")
        }
        clipboardListener = null
    }

    private fun setupSignalStrengthListener() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(signalStrength: android.telephony.SignalStrength) {
                    currentSignalLevel = signalStrength.level // 0 to 4
                    sendTelemetryUpdate()
                }
            }
            try {
                telephonyManager.registerTelephonyCallback(mainExecutor, callback)
                telephonyCallback = callback
            } catch (e: Exception) {
                Log.e("JanusService", "Error registering TelephonyCallback", e)
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : android.telephony.PhoneStateListener() {
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onSignalStrengthsChanged(signalStrength: android.telephony.SignalStrength) {
                    currentSignalLevel = signalStrength.level // 0 to 4
                    sendTelemetryUpdate()
                }
            }
            try {
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
                phoneStateListener = listener
            } catch (e: Exception) {
                Log.e("JanusService", "Error registering PhoneStateListener", e)
            }
        }
    }

    private fun cleanupSignalStrengthListener() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { callback ->
                try {
                    telephonyManager.unregisterTelephonyCallback(callback)
                } catch (e: Exception) {
                    Log.e("JanusService", "Error unregistering TelephonyCallback", e)
                }
            }
        } else {
            phoneStateListener?.let { listener ->
                try {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_NONE)
                } catch (e: Exception) {
                    Log.e("JanusService", "Error unregistering PhoneStateListener", e)
                }
            }
        }
    }

    private fun syncCalls() {
        if (!isConnected) return
        
        val callsArray = com.google.gson.JsonArray()
        val uri = android.provider.CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            android.provider.CallLog.Calls.NUMBER,
            android.provider.CallLog.Calls.DATE,
            android.provider.CallLog.Calls.DURATION,
            android.provider.CallLog.Calls.TYPE,
            android.provider.CallLog.Calls.CACHED_NAME
        )
        
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    "${android.provider.CallLog.Calls.DATE} DESC LIMIT 50"
                )?.use { cursor ->
                    val numIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                    val dateIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.DATE)
                    val durIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                    val typeIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                    val nameIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                    
                    while (cursor.moveToNext()) {
                        val number = if (numIdx != -1) cursor.getString(numIdx) ?: "Unknown" else "Unknown"
                        val date = if (dateIdx != -1) cursor.getLong(dateIdx) else 0L
                        val duration = if (durIdx != -1) cursor.getInt(durIdx) else 0
                        val type = if (typeIdx != -1) cursor.getInt(typeIdx) else 1
                        val name = if (nameIdx != -1) cursor.getString(nameIdx) ?: "" else ""
                        
                        val callObj = JsonObject().apply {
                            addProperty("number", number)
                            addProperty("date", date)
                            addProperty("duration", duration)
                            addProperty("type", when (type) {
                                android.provider.CallLog.Calls.INCOMING_TYPE -> "incoming"
                                android.provider.CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                android.provider.CallLog.Calls.MISSED_TYPE -> "missed"
                                else -> "other"
                            })
                            addProperty("name", if (name.isNotEmpty()) name else resolveContactName(this@JanusService, number))
                        }
                        callsArray.add(callObj)
                    }
                }
            } catch (e: Exception) {
                Log.e("JanusService", "Error querying CallLog ContentProvider", e)
            }
        } else {
            Log.w("JanusService", "READ_CALL_LOG permission not granted for sync")
        }
        
        val payload = JsonObject().apply {
            add("calls", callsArray)
        }
        val packet = Packet(
            type = "calls.list",
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis() / 1000,
            payload = payload
        )
        connectionManager?.sendPacket(packet)
    }

    private fun syncSms() {
        if (!isConnected) return
        
        val smsArray = com.google.gson.JsonArray()
        val uri = android.net.Uri.parse("content://sms")
        val projection = arrayOf(
            "address",
            "date",
            "body",
            "type"
        )
        
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    "date DESC LIMIT 50"
                )?.use { cursor ->
                    val addrIdx = cursor.getColumnIndex("address")
                    val dateIdx = cursor.getColumnIndex("date")
                    val bodyIdx = cursor.getColumnIndex("body")
                    val typeIdx = cursor.getColumnIndex("type")
                    
                    while (cursor.moveToNext()) {
                        val address = if (addrIdx != -1) cursor.getString(addrIdx) ?: "Unknown" else "Unknown"
                        val date = if (dateIdx != -1) cursor.getLong(dateIdx) else 0L
                        val body = if (bodyIdx != -1) cursor.getString(bodyIdx) ?: "" else ""
                        val type = if (typeIdx != -1) cursor.getInt(typeIdx) else 1
                        
                        val smsObj = JsonObject().apply {
                            addProperty("address", address)
                            addProperty("date", date)
                            addProperty("body", body)
                            addProperty("type", when (type) {
                                1 -> "inbox"
                                2 -> "sent"
                                else -> "other"
                            })
                            addProperty("name", resolveContactName(this@JanusService, address))
                        }
                        smsArray.add(smsObj)
                    }
                }
            } catch (e: Exception) {
                Log.e("JanusService", "Error querying SMS ContentProvider", e)
            }
        } else {
            Log.w("JanusService", "READ_SMS permission not granted for sync")
        }
        
        val payload = JsonObject().apply {
            add("sms", smsArray)
        }
        val packet = Packet(
            type = "sms.list",
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis() / 1000,
            payload = payload
        )
        connectionManager?.sendPacket(packet)
    }
}
