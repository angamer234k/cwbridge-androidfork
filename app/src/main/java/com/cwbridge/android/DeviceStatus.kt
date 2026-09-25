package com.cwbridge.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.SystemClock

/**
 * Limited device status for invoke|status — no SSID / wifi name, no location.
 */
object DeviceStatus {

    data class Snapshot(
        val batteryPercent: Int,
        val charging: Boolean,
        val wifiRssiDbm: Int?,
        val wifiLevel: Int?, // 0–4 or null
        val uptimeMs: Long,
    ) {
        fun toPayload(): String {
            val wifi = when {
                wifiRssiDbm == null -> "wifi=off"
                else -> "wifi_rssi=${wifiRssiDbm}dBm wifi_level=${wifiLevel ?: -1}/4"
            }
            val bat = "battery=$batteryPercent% charging=$charging"
            val up = "uptime_s=${uptimeMs / 1000}"
            return "$bat $wifi $up"
        }
    }

    fun read(context: Context): Snapshot {
        val battery = readBattery(context)
        val wifi = readWifi(context)
        return Snapshot(
            batteryPercent = battery.first,
            charging = battery.second,
            wifiRssiDbm = wifi?.first,
            wifiLevel = wifi?.second,
            uptimeMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun readBattery(context: Context): Pair<Int, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    @Suppress("DEPRECATION")
    private fun readWifi(context: Context): Pair<Int, Int>? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo ?: return null
            val rssi = info.rssi
            // rssi of -127 often means not connected
            if (rssi <= -127) return null
            val level = WifiManager.calculateSignalLevel(rssi, 5)
            rssi to level
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }
}
