package com.janus.app.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class ConnectionManager(
    private val context: Context,
    private val identity: Identity,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onPacketReceived: (Packet) -> Unit
) {
    var onBinaryReceived: ((ByteArray) -> Unit)? = null
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    var connectedIp: String? = null
        private set
    var connectedPort: Int? = null
        private set
    var connectedFingerprint: String? = null
        private set
    var isConnected: Boolean = false
        private set
    var isConnecting: Boolean = false
        private set

    private var autoReconnectEnabled = true
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempts = 0

    fun savePairedDevice(fingerprint: String) {
        val prefs = context.getSharedPreferences("janus_prefs", Context.MODE_PRIVATE)
        val pairedDevices = prefs.getStringSet("paired_devices", null) ?: emptySet()
        val newSet = pairedDevices.toMutableSet()
        newSet.add(fingerprint)
        prefs.edit().putStringSet("paired_devices", newSet).apply()
    }

    fun removePairedDevice(fingerprint: String) {
        val prefs = context.getSharedPreferences("janus_prefs", Context.MODE_PRIVATE)
        val pairedDevices = prefs.getStringSet("paired_devices", null) ?: emptySet()
        val newSet = pairedDevices.toMutableSet()
        newSet.remove(fingerprint)
        prefs.edit().putStringSet("paired_devices", newSet).apply()
    }

    fun isFingerprintPaired(fingerprint: String): Boolean {
        val prefs = context.getSharedPreferences("janus_prefs", Context.MODE_PRIVATE)
        val pairedDevices = prefs.getStringSet("paired_devices", null)
        return pairedDevices?.contains(fingerprint) == true
    }

    fun getPairedDevices(): Set<String> {
        val prefs = context.getSharedPreferences("janus_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("paired_devices", emptySet()) ?: emptySet()
    }

    fun connectToDevice(
        ip: String,
        port: Int,
        expectedFingerprint: String? = null,
        pairingPin: String? = null,
        onPairingResult: ((Boolean, String?) -> Unit)? = null
    ) {
        cancelPendingReconnect()
        
        if (isConnected && connectedIp == ip && connectedPort == port) {
            Log.d("JanusConnection", "Already connected to $ip:$port")
            return
        }

        disconnectSocketOnly()

        this.connectedIp = ip
        this.connectedPort = port
        this.connectedFingerprint = expectedFingerprint
        this.isConnecting = true

        val url = "wss://$ip:$port/api/v1/ws"
        Log.d("JanusConnection", "Initiating WebSocket connection to $url (attempt: ${reconnectAttempts + 1})")

        val sslContext = SSLContext.getInstance("TLS")
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Server certificate chain is empty")
                }
                val cert = chain[0]
                val derBytes = cert.encoded
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val digest = md.digest(derBytes)
                val serverFingerprint = digest.joinToString("") { "%02x".format(it) }

                Log.d("JanusConnection", "Server cert SHA-256: $serverFingerprint")

                if (expectedFingerprint != null && serverFingerprint != expectedFingerprint) {
                    Log.w("JanusConnection", "Fingerprint mismatch: expected=$expectedFingerprint, actual=$serverFingerprint")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        sslContext.init(null, arrayOf(trustManager), SecureRandom())

        val customClient = client.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(10, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()

        webSocket = customClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d("JanusConnection", "🟢 WebSocket connected to $ip:$port")
                isConnecting = false
                isConnected = true
                reconnectAttempts = 0
                mainHandler.post { onConnectionStateChanged(true) }

                // Send register packet immediately
                val registerPayload = JsonObject().apply {
                    addProperty("fingerprint", identity.fingerprint)
                    addProperty("device_name", android.os.Build.MODEL)
                    addProperty("device_type", "android")
                    DiscoveryManager.getLocalWifiIp(context)?.let { addProperty("ip", it) }
                    addProperty("port", 53318)
                    pairingPin?.let { addProperty("pin", it) }
                }

                val packet = Packet(
                    type = "device.register",
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis() / 1000,
                    payload = registerPayload
                )
                ws.send(gson.toJson(packet))
                Log.d("JanusConnection", "Sent device.register packet to host")
                onPairingResult?.invoke(true, null)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val packet = gson.fromJson(text, Packet::class.java)
                    Log.d("JanusConnection", "Received packet: ${packet.type}")

                    if (packet.type == "registration.success" || packet.type == "pairing.response") {
                        val serverFingerprint = packet.payload?.get("fingerprint")?.asString
                        if (serverFingerprint != null) {
                            savePairedDevice(serverFingerprint)
                        }
                        onPairingResult?.invoke(true, null)
                    } else if (packet.type == "device.unpaired") {
                        val serverFingerprint = packet.payload?.get("fingerprint")?.asString
                        if (serverFingerprint != null) {
                            removePairedDevice(serverFingerprint)
                        }
                        Log.d("JanusConnection", "Device was unpaired by host. Disconnecting.")
                        disconnect()
                    }

                    onPacketReceived(packet)
                } catch (e: Exception) {
                    Log.e("JanusConnection", "Failed to parse message", e)
                }
            }

            override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                onBinaryReceived?.invoke(bytes.toByteArray())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d("JanusConnection", "WebSocket closing: $code / $reason")
                handleDisconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w("JanusConnection", "WebSocket failure: ${t.message}")
                handleDisconnect()
                onPairingResult?.invoke(false, t.message ?: "Connection failed")
            }
        })
    }

    private fun handleDisconnect() {
        isConnecting = false
        val wasConnected = isConnected
        isConnected = false
        mainHandler.post { onConnectionStateChanged(false) }

        if (autoReconnectEnabled && connectedIp != null && connectedPort != null) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        cancelPendingReconnect()
        reconnectAttempts++
        val delayMs = when {
            reconnectAttempts <= 3 -> 2000L
            reconnectAttempts <= 10 -> 4000L
            else -> 8000L
        }

        Log.d("JanusConnection", "Scheduling auto-reconnect in ${delayMs}ms (attempt $reconnectAttempts)")
        val targetIp = connectedIp ?: return
        val targetPort = connectedPort ?: 53317
        val targetFp = connectedFingerprint

        val runnable = Runnable {
            if (!isConnected && !isConnecting) {
                Log.d("JanusConnection", "Executing scheduled auto-reconnect...")
                connectToDevice(targetIp, targetPort, targetFp)
            }
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingReconnect() {
        reconnectRunnable?.let {
            mainHandler.removeCallbacks(it)
            reconnectRunnable = null
        }
    }

    private fun disconnectSocketOnly() {
        try {
            webSocket?.close(1000, "Normal reset")
        } catch (_: Exception) {}
        webSocket = null
        isConnecting = false
        isConnected = false
    }

    fun sendPacket(packet: Packet) {
        webSocket?.let {
            val json = gson.toJson(packet)
            it.send(json)
        }
    }

    fun sendBinary(bytes: ByteArray) {
        webSocket?.let {
            it.send(bytes.toByteString(0, bytes.size))
        }
    }

    fun disconnect() {
        autoReconnectEnabled = false
        cancelPendingReconnect()
        disconnectSocketOnly()
        this.connectedIp = null
        this.connectedPort = null
        this.connectedFingerprint = null
        mainHandler.post { onConnectionStateChanged(false) }
    }

    fun uploadFileToConnectedDevice(
        fileName: String,
        fileSize: Long,
        fileInputStream: java.io.InputStream,
        onProgress: (Long) -> Unit,
        onResult: (Boolean, String?) -> Unit
    ) {
        val ip = connectedIp
        val port = connectedPort
        val expectedFingerprint = connectedFingerprint

        if (ip == null || port == null) {
            onResult(false, "Not connected to any device")
            return
        }

        Thread {
            try {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val tempFile = java.io.File.createTempFile("janus_upload_", null, context.cacheDir)
                try {
                    tempFile.outputStream().buffered(65536).use { fos ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        while (fileInputStream.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                            md.update(buffer, 0, read)
                        }
                    }
                    fileInputStream.close()

                    val digest = md.digest()
                    val fileHash = digest.joinToString("") { "%02x".format(it) }
                    val actualFileSize = tempFile.length()

                    val preparePayload = JsonObject().apply {
                        val filesArray = com.google.gson.JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("name", fileName)
                                addProperty("size", actualFileSize)
                                addProperty("hash", fileHash)
                            })
                        }
                        add("files", filesArray)
                    }

                    val sslContext = SSLContext.getInstance("TLS")
                    val trustManager = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                    sslContext.init(null, arrayOf(trustManager), SecureRandom())

                    val httpClient = OkHttpClient.Builder()
                        .sslSocketFactory(sslContext.socketFactory, trustManager)
                        .hostnameVerifier { _, _ -> true }
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(0, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build()

                    val prepareUrl = "https://$ip:$port/api/v1/prepare-upload"
                    val prepareRequest = Request.Builder()
                        .url(prepareUrl)
                        .post(preparePayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()

                    httpClient.newCall(prepareRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            onResult(false, "Prepare upload failed: ${response.message}")
                            return@Thread
                        }

                        val respBody = response.body?.string() ?: ""
                        val respJson = gson.fromJson(respBody, JsonObject::class.java)
                        val sessionId = respJson.get("session_id")?.asString ?: ""

                        if (sessionId.isEmpty()) {
                            onResult(false, "Did not receive session ID")
                            return@Thread
                        }

                        val uploadUrl = "https://$ip:$port/api/v1/upload/$sessionId/$fileHash"
                        val requestBody = object : RequestBody() {
                            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                            override fun contentLength(): Long = actualFileSize
                            override fun writeTo(sink: okio.BufferedSink) {
                                var uploaded = 0L
                                val buffer = ByteArray(65536)
                                tempFile.inputStream().buffered(65536).use { fis ->
                                    var read: Int
                                    while (fis.read(buffer).also { read = it } != -1) {
                                        sink.write(buffer, 0, read)
                                        uploaded += read
                                        onProgress(uploaded)
                                    }
                                }
                            }
                        }

                        val uploadRequest = Request.Builder()
                            .url(uploadUrl)
                            .post(requestBody)
                            .build()

                        httpClient.newCall(uploadRequest).execute().use { uploadResponse ->
                            if (uploadResponse.isSuccessful) {
                                onResult(true, null)
                            } else {
                                onResult(false, "Upload failed: ${uploadResponse.message}")
                            }
                        }
                    }
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                Log.e("JanusConnection", "Failed to upload file", e)
                onResult(false, e.message ?: "Unknown upload error")
            }
        }.start()
    }
}
