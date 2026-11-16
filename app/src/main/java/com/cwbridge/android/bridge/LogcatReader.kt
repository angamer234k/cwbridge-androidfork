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
                    // pidof/ps only works when shell can see other apps (Shizuku).
                    // READ_LOGS alone cannot list pids (hidepid), but it CAN read
                    // the log. Don't sit on "waiting for Roblox process" forever.
                    val robloxPid = resolveRobloxPid()
                    if (!isActive || !wantRunning) break
                    if (reconnectRequested) continue

                    val cmd = mutableListOf("logcat", "-v", "threadtime", "-T", "1")
                    if (robloxPid != null && robloxPid > 0) {
                        LogBuffer.i("Logcat", "filtering to Roblox pid=$robloxPid (attempt $attempt)")
                        cmd += "--pid=$robloxPid"
                    } else {
                        LogBuffer.w(
                            "Logcat",
                            "Roblox pid not found — ${com.cwbridge.android.ShizukuShell.statusLine()}. " +
                                "Tailing logcat without --pid. " +
                                "pidof is empty when Shizuku is off (Android hides other apps' /proc).",
                        )
                    }
                    val proc = ProcessBuilder(cmd)
                        .redirectErrorStream(true).start()
                    process = proc
                    if (attempt == 1) {
                        LogBuffer.i("Logcat", "attached — Roblox-only tail")
                    } else {
                        LogBuffer.i("Logcat", "re-attached (attempt $attempt, pid=${robloxPid ?: "any"})")
                    }

                    val myPkg = context.packageName
                    val myPid = android.os.Process.myPid()
                    var lastAliveCheck = System.currentTimeMillis()
                    var attachedAt = lastAliveCheck
                    var sawRoblox = false
                    var warnedNoLines = false
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        while (isActive && wantRunning && !reconnectRequested) {
                            val now = System.currentTimeMillis()
                            if (now - lastAliveCheck >= 1500L) {
                                lastAliveCheck = now
                                if (robloxPid != null && robloxPid > 0 && !isPidAlive(robloxPid)) {
                                    LogBuffer.w("Logcat", "Roblox pid $robloxPid gone — reconnect")
                                    break
                                }
                                try {
                                    proc.exitValue()
                                    LogBuffer.w("Logcat", "logcat process exited — reconnect")
                                    break
                                } catch (_: IllegalThreadStateException) {
                                }
                                if (!sawRoblox && !warnedNoLines && now - attachedAt >= 8_000L) {
                                    warnedNoLines = true
                                    LogBuffer.w(
                                        "Logcat",
                                        "no Roblox lines after 8s. If Roblox is open, READ_LOGS was revoked — " +
                                            "open Helper and grant it again (allow the logs popup, or rerun the adb grant).",
                                    )
                                }
                            }

                            if (!reader.ready()) {
                                delay(150)
                                continue
                            }
                            val line = reader.readLine() ?: break
                            if (linePid(line) == myPid || OWN_TAG.containsMatchIn(line)) continue
                            if (line.contains(myPkg) && !line.contains("invoke|")) continue
                            if (line.contains("attached —") || line.contains("re-attached")) continue
                            val unfiltered = robloxPid == null || robloxPid <= 0
                            if (unfiltered && !looksLikeRobloxLine(line)) continue
                            sawRoblox = true

                            RecentLogLines.add(line)
                            CatWebTracker.onLogLine(line)
                            AntiDisconnect.onLogLine(line)

                            val lower = line.lowercase()
                            val isFlog = lower.contains("flog::creatoroutput") ||
                                lower.contains("flog::output") ||
                                lower.contains("[from ")
                            val isInvoke = line.contains("invoke|")
                            // CatWeb status only — not every rbx.* / FFlag / FlagCache dump
                            val isCat = lower.contains("catweb") ||
                                lower.contains("waiting for server") ||
                                ((lower.contains("finished") || lower.contains("loading")) &&
                                    (line.contains('•') || line.contains('·') || lower.contains("catweb")))
                            if (isInvoke) {
                                try {
                                    invokeSink?.invoke(line)
                                } catch (t: Throwable) {
                                    LogBuffer.e("Logcat", "invokeSink: ${t.message}")
                                }
                            }
                            // Structured console: CatWeb + creator print only (no raw threadtime spam)
                            if (isFlog || isCat) {
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

    private fun linePid(line: String): Int? {
        val m = THREADTIME_PID.find(line) ?: return null
        return m.groupValues[1].toIntOrNull()
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
                // Process name can be the truncated comm (15 chars) so pidof misses.
                val (c2, ps) = com.cwbridge.android.ShizukuShell.exec(
                    "ps -A | grep -i roblox | grep -v grep",
                )
                if (c2 == 0) {
                    val fromPs = pidFromPs(ps)
                    if (fromPs != null) return fromPs
                }
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

    /** toybox `ps -A` columns are USER PID PPID ... NAME. Take the first real pid. */
    private fun pidFromPs(out: String): Int? {
        for (line in out.lineSequence()) {
            if (!line.contains("roblox", ignoreCase = true)) continue
            val pid = line.trim().split(Regex("\\s+"))
                .mapNotNull { it.toIntOrNull() }
                .firstOrNull { it > 50 }
            if (pid != null) return pid
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
        private val THREADTIME_PID = Regex(
            """^(?:\d{4}-)?\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+\d+\s""",
        )
        private val OWN_TAG = Regex(
            """\s[VDIWEF]\s+(ROBOT|Logcat|CatWeb|OCR|APP|BRIDGE|Shizuku|A11y|Control|AntiDC)\s*:""",
        )

        @Volatile
        var activeInstance: LogcatReader? = null
            private set

        fun requestReconnect(reason: String = "external") {
            activeInstance?.requestReconnect(reason)
        }
    }
}
