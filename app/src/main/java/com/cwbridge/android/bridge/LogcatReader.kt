package com.cwbridge.android.bridge

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
                    val robloxPid = resolveRobloxPid()
                    val proc = if (robloxPid != null && robloxPid > 0) {
                        LogBuffer.i("Logcat", "filtering to Roblox pid=$robloxPid")
                        ProcessBuilder(
                            "logcat",
                            "-v", "threadtime",
                            "-T", "1",
                            "--pid=$robloxPid",
                        ).redirectErrorStream(true).start()
                    } else {
                        LogBuffer.w("Logcat", "Roblox pid unknown — soft filter (no other packages in sink)")
                        ProcessBuilder(
                            "logcat",
                            "-v", "threadtime",
                            "-T", "1",
                            "*:V",
                        ).redirectErrorStream(true).start()
                    }
                    process = proc
                    if (attempt == 1) {
                        LogBuffer.i("Logcat", "attached — Roblox-only tail")
                    } else {
                        LogBuffer.i("Logcat", "re-attached (attempt $attempt, pid=$robloxPid)")
                    }
                    val myPkg = context.packageName
                    delay(50)
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        while (isActive && wantRunning) {
                            val line = reader.readLine() ?: break
                            // Drop our own noise and anything that is clearly not Roblox/CatWeb
                            if (line.contains(myPkg) && !line.contains("invoke|")) continue
                            if (line.contains("attached —") || line.contains("re-attached")) continue
                            if (line.contains("I/Logcat") && line.contains("attached")) continue
                            if (robloxPid == null && !looksLikeRobloxLine(line)) continue

                            RecentLogLines.add(line)
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


    /** Prefer Shizuku pidof; fall back to plain pidof (often empty without shell). */
    private fun resolveRobloxPid(): Int? {
        val pkgs = listOf(
            "com.roblox.client",
            "com.roblox.client.vng",
            "com.roblox.client.ugc",
        )
        // Shizuku first
        try {
            if (com.cwbridge.android.ShizukuShell.isReady()) {
                val joined = pkgs.joinToString(" ")
                val (code, out) = com.cwbridge.android.ShizukuShell.exec("pidof $joined")
                val pid = out.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                if (code == 0 && pid != null && pid > 0) return pid
            }
        } catch (_: Throwable) {
        }
        // Best-effort local
        try {
            for (pkg in pkgs) {
                val p = Runtime.getRuntime().exec(arrayOf("pidof", pkg))
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                val pid = out.split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                if (pid != null && pid > 0) return pid
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /** Soft filter when PID is unknown — keep Roblox/CatWeb/FLog/invoke only. */
    private fun looksLikeRobloxLine(line: String): Boolean {
        val lower = line.lowercase()
        if (lower.contains("roblox")) return true
        if (lower.contains("flog::")) return true
        if (lower.contains("catweb")) return true
        if (line.contains("invoke|")) return true
        if (line.contains('\u2022') || line.contains('\u00B7')) return true
        if (lower.contains("waiting for server")) return true
        // creator console markers
        if (lower.contains("creatoroutput") || lower.contains("[from ")) return true
        return false
    }

    fun stop() {
        wantRunning = false
        job?.cancel()
        job = null
        try { process?.destroy() } catch (_: Exception) {}
        process = null
    }
}
