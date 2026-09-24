package com.cwbridge.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Tries to attach to system logcat when READ_LOGS is granted.
 * Without the privilege, [LogBuffer] still captures CWBridge's own lines.
 */
class LogcatReader(private val context: Context) {

    private var job: Job? = null

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_LOGS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun start(scope: CoroutineScope) {
        stop()
        if (!hasPermission()) {
            LogBuffer.w("Logcat", "READ_LOGS not granted — using process-local buffer only")
            LogBuffer.i(
                "Logcat",
                "grant with: adb shell pm grant ${context.packageName} android.permission.READ_LOGS",
            )
            return
        }
        job = scope.launch(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder("logcat", "-v", "time", "*:I")
                    .redirectErrorStream(true)
                    .start()
                LogBuffer.i("Logcat", "attached to system logcat")
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.contains("CWBridge") || line.contains("A11y") || line.contains("Roblox")) {
                            LogBuffer.d("sys", line.take(240))
                        }
                    }
                }
            } catch (t: Throwable) {
                LogBuffer.e("Logcat", "failed to attach: ${t.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
