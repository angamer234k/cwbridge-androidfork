package com.cwbridge.android.server

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
import com.cwbridge.android.bridge.LogBuffer

/**
 * Advertises the local web panel as an NSD service `_cwbridge._tcp`.
 * Android will not let an app claim a custom hostname like `cwbridge.local`;
 * clients discover this service and then use the resolved host + port.
 */
class NsdAdvertiser(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val nsd = app.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private var generation = 0

    fun start(port: Int) {
        if (port !in 1..65535) return
        val gen = synchronized(this) { ++generation }
        main.post {
            if (gen != generation) return@post
            if (!canAdvertise()) {
                LogBuffer.w("NSD", "nearby-devices permission missing — not advertising")
                return@post
            }
            unregister()
            acquireLock()
            val info = NsdServiceInfo().apply {
                serviceName = SERVICE_NAME
                serviceType = SERVICE_TYPE
                this.port = port
                setAttribute("path", "/")
                setAttribute("app", "cwbridge")
            }
            val reg = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(si: NsdServiceInfo) {
                    LogBuffer.i("NSD", "advertising ${si.serviceName} type $SERVICE_TYPE port $port")
                }

                override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) {
                    LogBuffer.w("NSD", "register failed code=$code")
                }

                override fun onServiceUnregistered(si: NsdServiceInfo) {
                    LogBuffer.i("NSD", "unregistered")
                }

                override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {
                    LogBuffer.w("NSD", "unregister failed code=$code")
                }
            }
            listener = reg
            try {
                nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
            } catch (t: Throwable) {
                listener = null
                releaseLock()
                LogBuffer.w("NSD", "register threw ${t.message}")
            }
        }
    }

    fun stop() {
        val gen = synchronized(this) { ++generation }
        main.post {
            if (gen != generation) return@post
            unregister()
            releaseLock()
        }
    }

    private fun unregister() {
        val reg = listener
        listener = null
        if (reg != null) {
            try {
                nsd?.unregisterService(reg)
            } catch (_: Exception) {
            }
        }
    }

    private fun canAdvertise(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(app, Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun acquireLock() {
        if (lock != null) return
        val wifi = app.getSystemService(WifiManager::class.java) ?: return
        lock = wifi.createMulticastLock("cwbridge-nsd").apply {
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
        const val SERVICE_NAME = "CWBridge"
    }
}
