package com.cwbridge.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.ArrayDeque

/**
 * Browses `_cwbridge._tcp` and resolves each hit to host + port.
 * Only one resolve runs at a time — NsdManager requires that.
 */
class BridgeDiscovery(
    context: Context,
    private val onUpdate: (List<FoundBridge>) -> Unit,
) {
    data class FoundBridge(
        val name: String,
        val host: String,
        val port: Int,
    ) {
        val url: String get() = "http://$host:$port/"
        val label: String get() = "$name\n$url"
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val found = LinkedHashMap<String, FoundBridge>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var discovery: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        stop()
        found.clear()
        emit()
        if (!canDiscover()) return
        acquireLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains("_cwbridge._tcp") != true) return
                main.post {
                    if (discovery != null) enqueue(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                main.post {
                    found.remove(serviceInfo.serviceName)
                    emit()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { stop() }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = listener
        try {
            nsd?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Throwable) {
            discovery = null
            releaseLock()
        }
    }

    fun stop() {
        val listener = discovery
        discovery = null
        if (listener != null) {
            try {
                nsd?.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        resolving = false
        queue.clear()
        releaseLock()
    }

    fun permissionGranted(): Boolean = canDiscover()

    private fun canDiscover(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(app, Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun enqueue(info: NsdServiceInfo) {
        queue.add(info)
        pump()
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        if (resolving) return
        val next = queue.poll() ?: return
        val manager = nsd ?: return
        resolving = true
        try {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving = false
                    pump()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val ip = serviceInfo.host?.hostAddress
                    if (!ip.isNullOrBlank() && serviceInfo.port > 0) {
                        found[serviceInfo.serviceName] = FoundBridge(
                            name = serviceInfo.serviceName ?: "CWBridge",
                            host = ip,
                            port = serviceInfo.port,
                        )
                        emit()
                    }
                    resolving = false
                    pump()
                }
            })
        } catch (_: Throwable) {
            resolving = false
        }
    }

    private fun emit() {
        onUpdate(found.values.toList())
    }

    private fun acquireLock() {
        if (lock != null) return
        val wifi = app.getSystemService(WifiManager::class.java) ?: return
        lock = wifi.createMulticastLock("cwbridge-discover").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLock() {
        try {
            lock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        lock = null
    }

    companion object {
        const val SERVICE_TYPE = "_cwbridge._tcp."
    }
}
