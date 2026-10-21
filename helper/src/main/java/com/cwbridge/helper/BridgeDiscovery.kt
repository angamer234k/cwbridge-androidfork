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
 * NsdManager allows one discovery and one resolve at a time. Starting a
 * second one, especially from inside a resolve callback, crashes the process.
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
    private var session = 0
    private var restartAfterStop = false
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        if (!canDiscover()) return
        if (discovery != null) {
            restartAfterStop = true
            if (!haltDiscovery()) {
                restartAfterStop = false
                begin(session)
            }
            return
        }
        begin(session)
    }

    fun stop() {
        session++
        restartAfterStop = false
        haltDiscovery()
        resolving = false
        queue.clear()
        releaseLock()
    }

    fun permissionGranted(): Boolean = canDiscover()

    private fun begin(epoch: Int) {
        if (epoch != session || discovery != null) return
        acquireLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (epoch != session) return
                if (serviceInfo.serviceType?.contains("_cwbridge._tcp") != true) return
                main.post {
                    if (epoch == session) enqueue(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (epoch != session) return@post
                    found.remove(keyOf(serviceInfo))
                    emit()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                main.post {
                    if (discovery === this) discovery = null
                    if (restartAfterStop && epoch == session) {
                        restartAfterStop = false
                        found.clear()
                        queue.clear()
                        resolving = false
                        emit()
                        begin(session)
                    }
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post {
                    if (discovery === this) discovery = null
                    restartAfterStop = false
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post {
                    if (discovery === this) discovery = null
                    if (restartAfterStop) {
                        restartAfterStop = false
                        begin(session)
                    }
                }
            }
        }
        discovery = listener
        try {
            nsd?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Throwable) {
            discovery = null
            releaseLock()
        }
    }

    private fun haltDiscovery(): Boolean {
        val listener = discovery ?: return false
        return try {
            nsd?.stopServiceDiscovery(listener)
            true
        } catch (_: Exception) {
            discovery = null
            false
        }
    }

    private fun canDiscover(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(app, Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun enqueue(info: NsdServiceInfo) {
        queue.add(info)
        main.post { pump() }
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        if (resolving) return
        val next = queue.poll() ?: return
        val manager = nsd ?: return
        val epoch = session
        resolving = true
        try {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        if (epoch != session) return@post
                        resolving = false
                        pump()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        if (epoch != session) return@post
                        try {
                            val ip = hostAddress(serviceInfo)
                            val port = serviceInfo.port
                            if (!ip.isNullOrBlank() && port > 0) {
                                val name = serviceInfo.serviceName?.takeIf { it.isNotBlank() } ?: "CWBridge"
                                found[keyOf(serviceInfo)] = FoundBridge(name, ip, port)
                                emit()
                            }
                        } catch (_: Throwable) {
                        }
                        resolving = false
                        pump()
                    }
                }
            })
        } catch (_: Throwable) {
            resolving = false
            main.postDelayed({ if (epoch == session) pump() }, 400)
        }
    }

    private fun hostAddress(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            val addresses = info.hostAddresses
            val v4 = addresses.firstOrNull { it.address.size == 4 } ?: addresses.firstOrNull()
            if (v4 != null) return v4.hostAddress
        }
        @Suppress("DEPRECATION")
        return info.host?.hostAddress
    }

    private fun keyOf(info: NsdServiceInfo): String {
        val name = info.serviceName?.takeIf { it.isNotBlank() } ?: "CWBridge"
        return name
    }

    private fun emit() {
        try {
            onUpdate(found.values.toList())
        } catch (_: Throwable) {
        }
    }

    private fun acquireLock() {
        if (lock?.isHeld == true) return
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
