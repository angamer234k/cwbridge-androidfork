package com.cwbridge.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    @Volatile private var wantRunning = false

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
        wantRunning = true
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && wantRunning) {
                attempt++
                try {
                    val proc = ProcessBuilder(
                        "logcat",
                        "-v", "threadtime",
                        "-T", "1",
                        "*:V",
                    ).redirectErrorStream(true).start()
                    process = proc
                    if (attempt == 1) {
                        LogBuffer.i("Logcat", "attached — live tail (-T 1)")
                    } else {
                        LogBuffer.i("Logcat", "re-attached (attempt $attempt)")
                    }
                    val myPkg = context.packageName
                    delay(50)
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        while (isActive && wantRunning) {
                            val line = reader.readLine() ?: break
                            if (line.contains(myPkg) && !line.contains("invoke|")) continue
                            if (line.contains("attached —") || line.contains("re-attached")) continue
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
                                    if (isFlog || hasBullet) {
                                        val st = BridgeStatus.state
                                        if (st == OverlayState.WAITING || st == OverlayState.IDLE) {
                                            BridgeStatus.set(OverlayState.ACTIVE, "Roblox console")
                                        }
                                    }
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
                    try { proc.destroy() } catch (_: Exception) {}
                    process = null
                } catch (t: Throwable) {
                    process = null
                    if (isActive && wantRunning) {
                        LogBuffer.e("Logcat", "failed: ${t.message}")
                        BridgeStatus.set(OverlayState.ERROR, "Logcat failed")
                    }
                }
                if (!isActive || !wantRunning) break
                delay(500)
            }
        }
    }

    fun stop() {
        wantRunning = false
        job?.cancel()
        job = null
        try { process?.destroy() } catch (_: Exception) {}
        process = null
    }
}
