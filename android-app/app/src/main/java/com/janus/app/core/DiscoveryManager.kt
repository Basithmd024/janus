package com.janus.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.Inet4Address
import java.net.NetworkInterface

class DiscoveryManager(
    private val context: Context,
    private val onDeviceFound: (NsdServiceInfo) -> Unit,
    private val onDeviceRemoved: (NsdServiceInfo) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_janus._tcp."
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // MOB-03 FIX: Coroutine Channel queue for serialized NsdServiceInfo resolution without collisions
    private val resolveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    init {
        resolveScope.launch {
            for (serviceInfo in resolveQueue) {
                resolveServiceSequentially(serviceInfo)
            }
        }
    }

    private suspend fun resolveServiceSequentially(serviceInfo: NsdServiceInfo) {
        suspendCancellableCoroutine { continuation ->
            try {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e("JanusDiscovery", "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        if (continuation.isActive) continuation.resume(Unit, onCancellation = null)
                    }

                    override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                        Log.d("JanusDiscovery", "Resolve Succeeded: ${resolvedServiceInfo.serviceName} at ${resolvedServiceInfo.host}:${resolvedServiceInfo.port}")
                        onDeviceFound(resolvedServiceInfo)
                        if (continuation.isActive) continuation.resume(Unit, onCancellation = null)
                    }
                })
            } catch (e: Exception) {
                Log.e("JanusDiscovery", "Exception during resolveService", e)
                if (continuation.isActive) continuation.resume(Unit, onCancellation = null)
            }
        }
    }

    // MOB-07 FIX: Modern IP routing using ConnectivityManager prioritizing Wi-Fi over VPN/Cellular
    companion object {
        fun getLocalWifiIp(context: Context): String? {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    val activeNetwork = cm.activeNetwork
                    if (activeNetwork != null) {
                        val caps = cm.getNetworkCapabilities(activeNetwork)
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            val linkProperties = cm.getLinkProperties(activeNetwork)
                            val wifiAddress = linkProperties?.linkAddresses
                                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                                ?.address?.hostAddress
                            if (!wifiAddress.isNullOrEmpty()) {
                                return wifiAddress
                            }
                        }
                    }
                }

                // Fallback: search explicitly for wlan interface
                val interfaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
                for (iface in interfaces) {
                    if (iface.name.startsWith("wlan", ignoreCase = true) || iface.name.startsWith("eth", ignoreCase = true)) {
                        for (addr in java.util.Collections.list(iface.inetAddresses)) {
                            if (!addr.isLoopbackAddress && addr is Inet4Address) {
                                return addr.hostAddress
                            }
                        }
                    }
                }

                // General fallback
                for (iface in interfaces) {
                    if (!iface.name.startsWith("tun") && !iface.name.startsWith("rmnet") && !iface.name.startsWith("dummy")) {
                        for (addr in java.util.Collections.list(iface.inetAddresses)) {
                            if (!addr.isLoopbackAddress && addr is Inet4Address) {
                                return addr.hostAddress
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("JanusDiscovery", "Error getting local Wi-Fi IP address", e)
            }
            return null
        }
    }

    fun startAdvertising(deviceName: String, port: Int, fingerprint: String) {
        if (registrationListener != null) return

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = "$deviceName.${fingerprint.take(8)}"
            this.serviceType = this@DiscoveryManager.serviceType
            this.port = port
            // Add attributes (TXTs)
            setAttribute("dn", deviceName)
            setAttribute("fn", fingerprint)
            setAttribute("dt", "android")
            setAttribute("pv", "1")
            getLocalWifiIp(context)?.let { setAttribute("ip", it) }
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d("JanusDiscovery", "Service registered successfully: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("JanusDiscovery", "Service registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d("JanusDiscovery", "Service unregistered successfully")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("JanusDiscovery", "Service unregistration failed: $errorCode")
            }
        }

        nsdManager.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener
        )
    }

    fun stopAdvertising() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.w("JanusDiscovery", "Error unregistering service", e)
            }
            registrationListener = null
        }
    }

    fun startBrowsing() {
        if (discoveryListener != null) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("JanusDiscovery", "Discovery start failed: $errorCode")
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (_: Exception) {}
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("JanusDiscovery", "Discovery stop failed: $errorCode")
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (_: Exception) {}
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d("JanusDiscovery", "Service discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d("JanusDiscovery", "Service discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d("JanusDiscovery", "Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType.contains(serviceType)) {
                    // MOB-03 FIX: Enqueue resolve request into serialized channel queue
                    resolveQueue.trySend(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d("JanusDiscovery", "Service lost: ${serviceInfo.serviceName}")
                onDeviceRemoved(serviceInfo)
            }
        }

        nsdManager.discoverServices(
            serviceType,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
        )
    }

    fun stopBrowsing() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.w("JanusDiscovery", "Error stopping service discovery", e)
            }
            discoveryListener = null
        }
    }

    fun cleanup() {
        stopBrowsing()
        stopAdvertising()
        resolveScope.cancel()
    }
}
