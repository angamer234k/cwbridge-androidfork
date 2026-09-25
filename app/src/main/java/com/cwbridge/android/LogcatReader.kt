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
            LogBuffer.w("Logcat", "READ_LOGS not granted")
            return
        }
        job = scope.launch(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder(
                    "logcat", "-b", "main", "-b", "system", "-v", "threadtime", "*:V",
                ).redirectErrorStream(true).start()
                process = proc
                LogBuffer.i("Logcat", "attached \u2014 watching CatWeb + console + invoke")
                val myPkg = context.packageName
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.contains(myPkg) && !line.contains("invoke|")) continue
                        if (line.contains("attached \u2014 watching")) continue
                        if (line.contains("I/Logcat") && line.contains("attached")) continue

                        CatWebTracker.onLogLine(line)
                        AntiDisconnect.onLogLine(line)

                        val lower = line.lowercase()
                        val isFlog = lower.contains("flog::creatoroutput") ||
                            lower.contains("flog::output")
                        val hasBullet = line.contains('\u2022') || line.contains('\u00B7')
                        val isInvoke = line.contains("invoke|")
                        val looksLikeSiteLog =
                            line.contains("\u2139") || line.contains("\u26A0") || line.contains("\u274C") ||
                                lower.contains("[from ")

                        when {
                            hasBullet || isFlog || looksLikeSiteLog -> {
                                RobloxLogBuffer.add(line)
                                if (isInvoke) {
                                    AntiDisconnect.noteActivity()
                                    invokeSink?.invoke(line)
                                }
                            }
                            isInvoke -> {
                                AntiDisconnect.noteActivity()
                                invokeSink?.invoke(line)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (isActive) {
                    LogBuffer.e("Logcat", "failed: ${t.message}")
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
