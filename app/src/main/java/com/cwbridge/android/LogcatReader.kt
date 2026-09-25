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
 * Attaches to system logcat when READ_LOGS is granted so Roblox FLog
 * `invoke|…` lines reach [InvokeEngine].
 */
class LogcatReader(
    private val context: Context,
    private var invokeSink: ((String) -> Unit)? = null,
) {

    private var job: Job? = null

    fun setInvokeSink(sink: ((String) -> Unit)?) {
        invokeSink = sink
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_LOGS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun start(scope: CoroutineScope) {
        stop()
        if (!hasPermission()) {
            LogBuffer.w("Logcat", "READ_LOGS not granted — Roblox invoke| lines won't be seen")
            LogBuffer.i(
                "Logcat",
                "grant with: adb shell pm grant ${context.packageName} android.permission.READ_LOGS",
            )
            return
        }
        job = scope.launch(Dispatchers.IO) {
            try {
                // Clear is optional; -v raw keeps body easy to scan for invoke|
                val proc = ProcessBuilder("logcat", "-v", "time", "*:I")
                    .redirectErrorStream(true)
                    .start()
                LogBuffer.i("Logcat", "attached to system logcat (watching invoke|)")
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        when {
                            line.contains("invoke|") -> {
                                LogBuffer.d("sys", line.take(300))
                                invokeSink?.invoke(line)
                            }
                            line.contains("CWBridge") || line.contains("A11y") ||
                                line.contains("Invoke") || line.contains("Roblox") -> {
                                LogBuffer.d("sys", line.take(240))
                            }
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
