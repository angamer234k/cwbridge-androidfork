#!/usr/bin/env python3
"""
1) Logcat: Roblox PID only (no other apps)
2) On CatWeb 'finished': open configured domains via URL bar
3) Disconnect failsafe counter + relaunch path
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch_logcat() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/LogcatReader.kt"
    t = p.read_text()
    if "--pid" in t and "resolveRobloxPid" in t:
        print("LogcatReader already PID-filtered")
        return

    # Replace ProcessBuilder args section to use Roblox PID when possible
    old_start = '''                    val proc = ProcessBuilder(
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
                            if (line.contains("I/Logcat") && line.contains("attached")) continue'''

    new_start = '''                    val robloxPid = resolveRobloxPid()
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
                            if (robloxPid == null && !looksLikeRobloxLine(line)) continue'''

    if old_start not in t:
        raise SystemExit("LogcatReader start block not found")
    t = t.replace(old_start, new_start, 1)

    helpers = r'''
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

'''
    if "fun resolveRobloxPid" not in t:
        t = t.replace("    fun stop() {", helpers + "    fun stop() {", 1)
    p.write_text(t)
    print("LogcatReader: Roblox PID filter")

def patch_catweb() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/CatWebTracker.kt"
    t = p.read_text()
    if "onReadyOnce" in t:
        print("CatWebTracker already has onReadyOnce")
        return
    # Add callback + fire on finished
    t = t.replace(
        "object CatWebTracker {",
        '''object CatWebTracker {

    /** Fired once when CatWeb prints finished — used to open domains on load. */
    @Volatile
    private var readyCallback: (() -> Unit)? = null

    @Volatile
    private var readyFired: Boolean = false

    fun setOnReadyOnce(cb: (() -> Unit)?) {
        readyCallback = cb
        readyFired = false
    }
''',
        1,
    )
    t = t.replace(
        "        seenBoot = false\n        ready = false\n        lastLine = \"\"",
        "        seenBoot = false\n        ready = false\n        readyFired = false\n        lastLine = \"\"",
        1,
    )
    old_fin = '''        if (finished) {
            ready = true
            BridgeStatus.set(OverlayState.ACTIVE, "CatWeb ready")
        }'''
    new_fin = '''        if (finished) {
            ready = true
            BridgeStatus.set(OverlayState.ACTIVE, "CatWeb ready")
            if (!readyFired) {
                readyFired = true
                try {
                    readyCallback?.invoke()
                } catch (t: Throwable) {
                    LogBuffer.e("CatWeb", "onReady: ${t.message}")
                }
            }
        }'''
    if old_fin not in t:
        raise SystemExit("finished block missing")
    t = t.replace(old_fin, new_fin, 1)
    p.write_text(t)
    print("CatWebTracker: onReadyOnce")

def patch_bridge_domains() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = p.read_text()
    if "openDomainsOnCwLoad" in t:
        print("openDomainsOnCwLoad exists")
    else:
        # Add failsafe + open on load after openDomains function end
        inject = r'''

    /** How many forced Roblox relaunches after a dead disconnect (no Reconnect). */
    @Volatile
    var disconnectFailsafe: Int = 0
        private set

    fun bumpDisconnectFailsafe(): Int {
        disconnectFailsafe += 1
        LogBuffer.w("Control", "disconnect failsafe now=$disconnectFailsafe")
        return disconnectFailsafe
    }

    fun resetDisconnectFailsafe() {
        disconnectFailsafe = 0
    }

    /**
     * Called when CatWeb logs "finished". Opens domains saved for auto-load
     * by focusing the URL bar (no Ctrl+T — mobile CatWeb is not Chrome).
     */
    fun openDomainsOnCwLoad(context: Context): String {
        val domains = loadAutoOpenDomains(context)
        if (domains.isEmpty()) {
            LogBuffer.i("Control", "CW load: no auto-open domains configured")
            return "no auto-open domains"
        }
        LogBuffer.i("Control", "CW load: opening ${domains.size} domain(s)")
        // First domain: just navigate current tab via URL bar.
        // Further domains: try tabs-count → + then URL bar.
        return openDomains(domains)
    }

    fun loadAutoOpenDomains(context: Context): List<String> {
        return try {
            com.cwbridge.android.data.UserFileStore.init(context.applicationContext)
            val raw = com.cwbridge.android.data.UserFileStore.settingsGet(
                context.applicationContext,
                "auto_open_domains",
                "",
            )
            raw.lines().flatMap { it.split(",", ";") }.map { it.trim() }.filter { it.isNotEmpty() }
        } catch (t: Throwable) {
            LogBuffer.w("Control", "loadAutoOpenDomains: ${t.message}")
            emptyList()
        }
    }

    fun saveAutoOpenDomains(context: Context, domains: List<String>) {
        com.cwbridge.android.data.UserFileStore.init(context.applicationContext)
        com.cwbridge.android.data.UserFileStore.settingsPut(
            context.applicationContext,
            "auto_open_domains",
            domains.joinToString("\n"),
        )
    }

'''
        # Insert before final closing brace of object
        if not t.rstrip().endswith("}"):
            raise SystemExit("BridgeControl unexpected ending")
        # find last }
        idx = t.rfind("}")
        t = t[:idx] + inject + "}\n"
        p.write_text(t)
        print("BridgeControl: openDomainsOnCwLoad + failsafe")

def patch_antidc() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/AntiDisconnect.kt"
    t = p.read_text()
    if "bumpDisconnectFailsafe" in t:
        print("AntiDC failsafe already")
        return
    # On disconnect signal without reconnect hint, bump failsafe and request relaunch
    old = '''        if (lower.contains("disconnect") || lower.contains("disconnected") ||
            lower.contains("connection lost")
        ) {
            LogBuffer.w("AntiDC", "disconnect signal — soft recover (no spam tap)")
            noteActivity()
            if (BridgeStatus.state != OverlayState.ERROR) {
                BridgeStatus.set(OverlayState.WAITING, "Reconnecting…")
            }
            // Do NOT tap on every disconnect line — that caused continuous edge taps
            // after logcat buffer replay. Idle watchdog still handles long silence.
        }'''
    new = '''        if (lower.contains("disconnect") || lower.contains("disconnected") ||
            lower.contains("connection lost")
        ) {
            LogBuffer.w("AntiDC", "disconnect signal — soft recover (no spam tap)")
            noteActivity()
            val hasReconnect = lower.contains("reconnect")
            if (!hasReconnect) {
                // Dead disconnect (no reconnect affordance in the log line).
                // OCR path will refine this; for now count + mark waiting.
                val n = BridgeControl.bumpDisconnectFailsafe()
                LogBuffer.w("AntiDC", "no reconnect hint — failsafe=$n")
            }
            if (BridgeStatus.state != OverlayState.ERROR) {
                BridgeStatus.set(OverlayState.WAITING, "Reconnecting…")
            }
            // Do NOT tap on every disconnect line — that caused continuous edge taps
            // after logcat buffer replay. Idle watchdog still handles long silence.
        }'''
    if old not in t:
        print("WARN: AntiDC disconnect block not exact")
    else:
        t = t.replace(old, new, 1)
        p.write_text(t)
        print("AntiDC: failsafe bump")

def patch_main() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/MainActivity.kt"
    t = p.read_text()
    if "openDomainsOnCwLoad" in t:
        print("MainActivity already wires CW load domains")
        return
    # After CatWebTracker.reset() on bridge start, set callback
    needle = "CatWebTracker.reset()"
    if needle not in t:
        print("WARN: CatWebTracker.reset not found")
        return
    insert = '''CatWebTracker.reset()
            CatWebTracker.setOnReadyOnce {
                Thread {
                    try {
                        // Small settle delay after "finished"
                        Thread.sleep(800)
                        val msg = BridgeControl.openDomainsOnCwLoad(applicationContext)
                        LogBuffer.i("CWBridge", "domains on CW load: $msg")
                    } catch (t: Throwable) {
                        LogBuffer.e("CWBridge", "domains on CW load: ${t.message}")
                    }
                }.start()
            }'''
    # Only replace first occurrence in start path — careful
    t = t.replace(needle, insert, 1)
    p.write_text(t)
    print("MainActivity: domains on CW finished")

def main() -> None:
    patch_logcat()
    patch_catweb()
    patch_bridge_domains()
    patch_antidc()
    patch_main()
    print("done")

if __name__ == "__main__":
    main()
