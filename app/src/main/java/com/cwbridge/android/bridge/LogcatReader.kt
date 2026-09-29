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
import java.io.File
import java.io.InputStreamReader

/**
 * Roblox-only logcat tail.
 *
 * When Roblox is force-stopped, `logcat --pid=OLD` often **hangs** instead of EOF.
 * We poll PID liveness and destroy/re-attach so logs advance after Restart Roblox.
 */
class LogcatReader(
    private val context: Context,
    private var invokeSink: ((String) -> Unit)? = null,
) {

    private var job: Job? = null
    private var process: Process? = null
    @Volatile private var wantRunning = false
    @Volatile private var reconnectRequested = false

    fun setInvokeSink(sink: ((String) -> Unit)?) {
        invokeSink = sink
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_LOGS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Call after force-stop / restart so we drop the dead --pid session. */
    fun requestReconnect(reason: String = "manual") {
        LogBuffer.i("Logcat", "reconnect requested ($reason)")
        reconnectRequested = true
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
    }

    fun start(scope: CoroutineScope) {
        stop()
        if (!hasPermission()) {
            LogBuffer.w("Logcat", "READ_LOGS not granted")
            return
        }
        wantRunning = true
        reconnectRequested = false
        activeInstance = this
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && wantRunning) {
                attempt++
                reconnectRequested = false
                try {
                    // After restart Roblox may not exist yet — wait for a PID
                    var robloxPid: Int? = null
                    var waitTicks = 0
                    while (isActive && wantRunning && !reconnectRequested) {
                        robloxPid = resolveRobloxPid()
                        if (robloxPid != null && robloxPid > 0) break
                        waitTicks++
                        if (waitTicks == 1 || waitTicks % 5 == 0) {
                            LogBuffer.i("Logcat", "waiting for Roblox process…")
                        }
                        delay(1000)
                    }
                    if (!isActive || !wantRunning) break
                    if (reconnectRequested) continue

                    val pid = robloxPid!!
                    LogBuffer.i("Logcat", "filtering to Roblox pid=$pid (attempt $attempt)")
                    val proc = ProcessBuilder(
                        "logcat",
                        "-v", "threadtime",
                        "-T", "1",
                        "--pid=$pid",
                    ).redirectErrorStream(true).start()
                    process = proc
                    if (attempt == 1) {
                        LogBuffer.i("Logcat", "attached — Roblox-only tail")
                    } else {
                        LogBuffer.i("Logcat", "re-attached (attempt $attempt, pid=$pid)")
                    }

                    val myPkg = context.packageName
                    var lastAliveCheck = System.currentTimeMillis()
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        while (isActive && wantRunning && !reconnectRequested) {
                            // Dead PID / hung logcat: poll every ~1.5s
                            val now = System.currentTimeMillis()
                            if (now - lastAliveCheck >= 1500L) {
                                lastAliveCheck = now
                                if (!isPidAlive(pid)) {
                                    LogBuffer.w("Logcat", "Roblox pid $pid gone — reconnect")
                                    break
                                }
                                // process died?
                                try {
                                    proc.exitValue()
                                    LogBuffer.w("Logcat", "logcat process exited — reconnect")
                                    break
                                } catch (_: IllegalThreadStateException) {
                                    // still running
                                }
                            }

                            if (!reader.ready()) {
                                delay(150)
                                continue
                            }
                            val line = reader.readLine() ?: break
                            if (line.contains(myPkg) && !line.contains("invoke|")) continue
                            if (line.contains("attached —") || line.contains("re-attached")) continue

                            RecentLogLines.add(line)
                            CatWebTracker.onLogLine(line)
                            AntiDisconnect.onLogLine(line)

                            val lower = line.lowercase()
                            val isFlog = lower.contains("flog::creatoroutput") ||
                                lower.contains("flog::output") ||
                                lower.contains("[from ")
                            val isInvoke = line.contains("invoke|")
                            val isCat = lower.contains("catweb") || lower.contains("waiting for server")
                            if (isFlog || isInvoke || isCat || looksLikeRobloxLine(line)) {
                                if (isInvoke) {
                                    try {
                                        invokeSink?.invoke(line)
                                    } catch (t: Throwable) {
                                        LogBuffer.e("Logcat", "invokeSink: ${t.message}")
                                    }
                                }
                                LogBuffer.i("Roblox", line.take(300))
                            }
                        }
                    }
                    try {
                        proc.destroy()
                    } catch (_: Exception) {
                    }
                    process = null
                } catch (t: Throwable) {
                    process = null
                    if (isActive && wantRunning) {
                        LogBuffer.e("Logcat", "failed: ${t.message}")
                    }
                }
                if (!isActive || !wantRunning) break
                delay(400)
            }
        }
    }

    private fun isPidAlive(pid: Int): Boolean {
        // /proc/pid exists while process lives
        try {
            if (File("/proc/$pid").exists()) return true
        } catch (_: Throwable) {
        }
        try {
            if (com.cwbridge.android.ShizukuShell.isReady()) {
                val (code, out) = com.cwbridge.android.ShizukuShell.exec("kill -0 $pid")
                // kill -0 succeeds (0) if process exists
                if (code == 0) return true
                if (out.contains("No such process", ignoreCase = true)) return false
            }
        } catch (_: Throwable) {
        }
        // Fallback: pidof should not list this pid
        val current = resolveRobloxPid()
        return current != null && current == pid
    }

    private fun resolveRobloxPid(): Int? {
        val pkgs = listOf(
            "com.roblox.client",
            "com.roblox.client.vng",
            "com.roblox.client.ugc",
        )
        try {
            if (com.cwbridge.android.ShizukuShell.isReady()) {
                val joined = pkgs.joinToString(" ")
                val (code, out) = com.cwbridge.android.ShizukuShell.exec("pidof $joined")
                val pid = out.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                if (code == 0 && pid != null && pid > 0) return pid
            }
        } catch (_: Throwable) {
        }
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

    private fun looksLikeRobloxLine(line: String): Boolean {
        val lower = line.lowercase()
        if (lower.contains("roblox")) return true
        if (lower.contains("flog::")) return true
        if (lower.contains("catweb")) return true
        if (line.contains("invoke|")) return true
        if (line.contains('\u2022') || line.contains('\u00B7')) return true
        if (lower.contains("waiting for server")) return true
        if (lower.contains("creatoroutput") || lower.contains("[from ")) return true
        if (lower.contains("datamodel loading")) return true
        if (lower.contains("hello world")) return true
        return false
    }

    fun stop() {
        wantRunning = false
        reconnectRequested = true
        job?.cancel()
        job = null
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
        if (activeInstance === this) activeInstance = null
    }

    companion object {
        @Volatile
        var activeInstance: LogcatReader? = null
            private set

        fun requestReconnect(reason: String = "external") {
            activeInstance?.requestReconnect(reason)
        }
    }
}
