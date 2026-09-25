package com.cwbridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object BridgeNotifier {
    private const val CHANNEL = "cwbridge_bridge"
    private const val ID = 42

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, "CWBridge status", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shows when the bridge is listening"
        nm.createNotificationChannel(ch)
    }

    fun showRunning(context: Context, detail: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("CWBridge running")
            .setContentText(detail)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(ID, n)
    }

    fun hide(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
