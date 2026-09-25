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
 * Reads system logcat for:
 * - CatWeb status lines (→ overlay yellow until "finished", then green)
 * - Roblox [FLog::CreatorOutput] / invoke| commands
 */
class LogcatReader(
    private val context: Context,
    private var invokeSink: ((String) -> Unit)? = null,
) {

    private var job: Job? = null
    private var process: Process? = null

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
            LogBuffer.w("Logcat", "READ_LOGS not granted — game console lines won't be seen")
            LogBuffer.i(
                "Logcat",
                "grant with: adb shell pm grant ${context.packageName} android.permission.READ_LOGS",
            )
            return
        }
        job = scope.launch(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder(
                    "logcat",
                    "-b", "main",
                    "-b", "system",
                    "-v", "threadtime",
                    "*:V",
                ).redirectErrorStream(true).start()
                process = proc
                LogBuffer.i("Logcat", "attached — watching CatWeb + invoke commands")
                val myPkg = context.packageName
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.contains(myPkg) && !line.contains("invoke|")) continue
                        if (line.contains("attached — watching")) continue
                        if (line.contains("I/Logcat") && line.contains("attached")) continue

                        CatWebTracker.onLogLine(line)

                        val isFlog = line.contains("FLog::CreatorOutput", ignoreCase = true) ||
                            line.contains("FLog::Output", ignoreCase = true)
                        val isInvoke = line.contains("invoke|")

                        when {
                            isFlog -> {
                                RobloxLogBuffer.add(line)
                                if (isInvoke) {
                                    LogBuffer.d("sys", line.take(300))
                                    invokeSink?.invoke(line)
                                }
                            }
                            isInvoke -> {
                                LogBuffer.d("sys", line.take(300))
                                invokeSink?.invoke(line)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (isActive) {
                    LogBuffer.e("Logcat", "failed to attach: ${t.message}")
                    BridgeStatus.set(OverlayState.ERROR, "Logcat failed")
                }
            } finally {
                process = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { process?.destroy() } catch (_: Exception) {}
        process = null
    }
}
