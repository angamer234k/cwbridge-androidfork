#!/usr/bin/env python3
"""One-shot source patches for CWBridge Android."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def must_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"MISSING block: {label}")
    return text.replace(old, new, 1)


def patch_local_http_server() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()
    t = t.replace(
        "private val port: Int = 8765,",
        "preferredPort: Int = 8080,",
        1,
    )
    t = must_replace(
        t,
        "    private val running = AtomicBoolean(false)\n"
        "    private var server: ServerSocket? = null",
        "    private val running = AtomicBoolean(false)\n"
        "    private var server: ServerSocket? = null\n"
        "    @Volatile private var boundPort: Int = 0",
        "server fields",
    )
    t = t.replace(
        "fun port(): Int = port",
        "fun port(): Int = if (boundPort > 0) boundPort else preferredPort",
        1,
    )
    old_start = (
        "    fun start() {\n"
        "        if (!running.compareAndSet(false, true)) return\n"
        "        thread(name = \"cwbridge-http\", isDaemon = true) {\n"
        "            try {\n"
        "                ServerSocket(port).use { ss ->\n"
        "                    server = ss\n"
        "                    LogBuffer.i(\"Server\", \"listening on port $port\")\n"
        "                    while (running.get()) {\n"
        "                        try {\n"
        "                            val socket = ss.accept()\n"
        "                            thread(name = \"cwbridge-http-conn\", isDaemon = true) {\n"
        "                                try {\n"
        "                                    handle(socket)\n"
        "                                } catch (t: Throwable) {\n"
        "                                    LogBuffer.w(\"Server\", \"connection failed: ${t.message}\")\n"
        "                                }\n"
        "                            }\n"
        "                        } catch (_: Exception) {\n"
        "                            if (!running.get()) break\n"
        "                        }\n"
        "                    }\n"
        "                }\n"
        "            } catch (t: Throwable) {\n"
        "                LogBuffer.e(\"Server\", \"failed: ${t.message}\")\n"
        "                running.set(false)\n"
        "            } finally {\n"
        "                server = null\n"
        "            }\n"
        "        }\n"
        "    }"
    )
    new_start = r'''    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "cwbridge-http", isDaemon = true) {
            // Prefer 8080, then legacy 8765, then 80 (often needs priv).
            val candidates = linkedSetOf(preferredPort, 8080, 8765, 80).filter { it in 1..65535 }
            var lastErr: Throwable? = null
            var started = false
            for (tryPort in candidates) {
                try {
                    val ss = ServerSocket(tryPort)
                    server = ss
                    boundPort = tryPort
                    LogBuffer.i("Server", "listening on port $tryPort (tried ${candidates.joinToString()})")
                    started = true
                    try {
                        while (running.get()) {
                            try {
                                val socket = ss.accept()
                                thread(name = "cwbridge-http-conn", isDaemon = true) {
                                    try {
                                        handle(socket)
                                    } catch (t: Throwable) {
                                        LogBuffer.w("Server", "connection failed: ${t.message}")
                                    }
                                }
                            } catch (_: Exception) {
                                if (!running.get()) break
                            }
                        }
                    } finally {
                        try { ss.close() } catch (_: Exception) {}
                    }
                    break
                } catch (t: Throwable) {
                    lastErr = t
                    LogBuffer.w("Server", "port $tryPort failed: ${t.message}")
                }
            }
            if (!started) {
                LogBuffer.e("Server", "all ports failed: ${lastErr?.message}")
                running.set(false)
            }
            server = null
            boundPort = 0
        }
    }'''
    t = must_replace(t, old_start, new_start, "LocalHttpServer.start")
    p.write_text(t)
    print("OK LocalHttpServer")


def patch_main_activity() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/MainActivity.kt"
    t = p.read_text()
    t = t.replace(
        "        val port = 8765\n"
        "        val server = LocalHttpServer(\n"
        "            context = applicationContext,\n"
        "            port = port,",
        "        val server = LocalHttpServer(\n"
        "            context = applicationContext,\n"
        "            preferredPort = 8080,",
        1,
    )
    t = t.replace(
        'Toast.makeText(this, "Server on port $port", Toast.LENGTH_SHORT).show()',
        'Toast.makeText(this, "Server starting (8080/8765/80)", Toast.LENGTH_SHORT).show()',
        1,
    )
    t = t.replace(
        "val port = localServer?.port() ?: 8765",
        "val port = localServer?.port() ?: 8080",
        1,
    )
    t = t.replace("https://<this-device-ip>", "http://<this-device-ip>")
    t = t.replace('"https://"', '"http://"')
    t = must_replace(
        t,
        "            bridgeRunning = true\n"
        "            CatWebTracker.reset()\n"
        "            AntiDisconnect.start(bridgeScope)\n"
        "            ensureLogcatRunning()\n"
        "            invokeEngine.start()",
        "            bridgeRunning = true\n"
        "            CatWebTracker.reset()\n"
        "            AntiDisconnect.start(bridgeScope)\n"
        "            ensureLogcatRunning()\n"
        "            BridgeControl.scheduleOpenCatWebIfNeeded(applicationContext, 10_000L)\n"
        "            invokeEngine.start()",
        "bridge start",
    )
    t = must_replace(
        t,
        "            bridgeRunning = false\n"
        "            logcatReader.stop()\n"
        "            invokeEngine.stop()\n"
        "            executionEngine.stop()\n"
        "            AntiDisconnect.stop()\n"
        '            LogBuffer.i("CWBridge", "bridge stopped")',
        "            bridgeRunning = false\n"
        "            BridgeControl.cancelOpenCatWeb()\n"
        "            logcatReader.stop()\n"
        "            invokeEngine.stop()\n"
        "            executionEngine.stop()\n"
        "            AntiDisconnect.stop()\n"
        '            LogBuffer.i("CWBridge", "bridge stopped")',
        "bridge stop",
    )
    # poll for bound port after start
    old_srv = (
        "        server.start()\n"
        '        Toast.makeText(this, "Server starting (8080/8765/80)", Toast.LENGTH_SHORT).show()\n'
        "        refreshServerUi()"
    )
    new_srv = (
        "        server.start()\n"
        "        Thread {\n"
        "            repeat(20) {\n"
        "                Thread.sleep(50)\n"
        "                if (server.isRunning() && server.port() > 0) {\n"
        "                    runOnUiThread {\n"
        '                        Toast.makeText(this, "Server on http://…:${server.port()}", Toast.LENGTH_LONG).show()\n'
        "                        refreshServerUi()\n"
        "                    }\n"
        "                    return@Thread\n"
        "                }\n"
        "            }\n"
        "            runOnUiThread {\n"
        '                Toast.makeText(this, "Server failed to bind (8080/8765/80)", Toast.LENGTH_LONG).show()\n'
        "                refreshServerUi()\n"
        "            }\n"
        "        }.start()\n"
        "        refreshServerUi()"
    )
    if old_srv in t:
        t = t.replace(old_srv, new_srv, 1)
    else:
        old2 = (
            "        server.start()\n"
            '        Toast.makeText(this, "Server on port $port", Toast.LENGTH_SHORT).show()\n'
            "        refreshServerUi()"
        )
        t = must_replace(t, old2, new_srv, "server.start toast")
    p.write_text(t)
    print("OK MainActivity")


def patch_bridge_control() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = p.read_text()
    if "scheduleOpenCatWebIfNeeded" in t:
        print("OK BridgeControl (already)")
        return
    if "import android.net.Uri" not in t:
        t = t.replace(
            "import android.content.Intent\n",
            "import android.content.Intent\n"
            "import android.net.Uri\n"
            "import android.os.Handler\n"
            "import android.os.Looper\n",
            1,
        )
    insert = r'''
    /** CatWeb: Make a Website! */
    const val CATWEB_PLACE_ID = "16855862021"
    private const val CATWEB_DEEPLINK = "roblox://placeId=$CATWEB_PLACE_ID"
    private const val CATWEB_HTTPS =
        "https://www.roblox.com/games/start?placeId=$CATWEB_PLACE_ID"

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var catwebOpenRunnable: Runnable? = null

    /** After delayMs, if CatWeb never booted, open the place via deeplink. */
    fun scheduleOpenCatWebIfNeeded(context: Context, delayMs: Long = 10_000L) {
        cancelOpenCatWeb()
        val appCtx = context.applicationContext
        val r = Runnable {
            catwebOpenRunnable = null
            if (CatWebTracker.seenBoot || CatWebTracker.ready) {
                LogBuffer.i("Control", "CatWeb already seen — skip auto-open")
                return@Runnable
            }
            LogBuffer.i("Control", "no CatWeb boot in ${delayMs}ms — opening deeplink")
            openCatWeb(appCtx)
        }
        catwebOpenRunnable = r
        mainHandler.postDelayed(r, delayMs)
        LogBuffer.i("Control", "scheduled CatWeb auto-open in ${delayMs}ms")
    }

    fun cancelOpenCatWeb() {
        catwebOpenRunnable?.let { mainHandler.removeCallbacks(it) }
        catwebOpenRunnable = null
    }

    fun openCatWeb(context: Context): String {
        return try {
            val deep = Intent(Intent.ACTION_VIEW, Uri.parse(CATWEB_DEEPLINK)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(deep)
                LogBuffer.i("Control", "opened $CATWEB_DEEPLINK")
                "opening CatWeb (deeplink)"
            } catch (t: Throwable) {
                LogBuffer.w("Control", "deeplink failed: ${t.message} — https fallback")
                val web = Intent(Intent.ACTION_VIEW, Uri.parse(CATWEB_HTTPS)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(web)
                "opening CatWeb (https fallback)"
            }
        } catch (t: Throwable) {
            LogBuffer.e("Control", "openCatWeb: ${t.message}")
            "open CatWeb failed: ${t.message}"
        }
    }

'''
    marker = "    /** True when this device can take screenshots (Android 11+). */"
    t = must_replace(t, marker, insert + marker, "BridgeControl insert")
    p.write_text(t)
    print("OK BridgeControl")


def patch_shizuku() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"
    t = p.read_text()
    old_ctrl = (
        "    fun pressCtrlT(): Boolean {\n"
        "        val ctrl = KeyEvent.KEYCODE_CTRL_LEFT\n"
        "        val t = KeyEvent.KEYCODE_T\n"
        "        val attempts = listOf(\n"
        '            "input keycombination $ctrl $t",\n'
        '            "cmd input keycombination $ctrl $t",\n'
        '            "input keyevent $ctrl $t",\n'
        "        )\n"
        "        for (cmd in attempts) {\n"
        '            LogBuffer.i("Shizuku", "try: $cmd")\n'
        "            val (code, out) = exec(cmd)\n"
        '            LogBuffer.i("Shizuku", "  exit=$code out=${out.take(100).ifBlank { \"(empty)\" }}")\n'
        "            if (code == 0) {\n"
        '                LogBuffer.i("Shizuku", "Ctrl+T OK via: $cmd")\n'
        "                return true\n"
        "            }\n"
        "        }\n"
        '        LogBuffer.w("Shizuku", "all Ctrl+T strategies failed — ${statusLine()}")\n'
        "        return false\n"
        "    }"
    )
    new_ctrl = r'''    fun pressCtrlT(): Boolean {
        val ctrl = KeyEvent.KEYCODE_CTRL_LEFT
        val tKey = KeyEvent.KEYCODE_T
        val attempts = listOf(
            "input keycombination $ctrl $tKey",
            "cmd input keycombination $ctrl $tKey",
            "toybox input keycombination $ctrl $tKey",
            "input keycombination 113 48",
            "cmd input keycombination 113 48",
            "input keyevent --longpress $ctrl $tKey",
            "input keyevent $ctrl $tKey",
            "input keyevent 113 48",
        )
        for (cmd in attempts) {
            LogBuffer.i("Shizuku", "try: $cmd")
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "  exit=$code out=${out.take(120).ifBlank { "(empty)" }}")
            if (code == 0) {
                LogBuffer.i("Shizuku", "Ctrl+T OK via: $cmd")
                return true
            }
        }
        LogBuffer.w("Shizuku", "all Ctrl+T strategies failed — ${statusLine()}")
        return false
    }'''
    t = must_replace(t, old_ctrl, new_ctrl, "pressCtrlT")
    old_enter = (
        "    fun pressEnter(): Boolean {\n"
        "        for (cmd in listOf(\n"
        '            "input keyevent ${KeyEvent.KEYCODE_ENTER}",\n'
        '            "cmd input keyevent ${KeyEvent.KEYCODE_ENTER}",\n'
        "        )) {\n"
        "            val (code, _) = exec(cmd)\n"
        "            if (code == 0) {\n"
        '                LogBuffer.i("Shizuku", "Enter OK via $cmd")\n'
        "                return true\n"
        "            }\n"
        "        }\n"
        "        return false\n"
        "    }"
    )
    new_enter = r'''    fun pressEnter(): Boolean {
        val enter = KeyEvent.KEYCODE_ENTER
        val attempts = listOf(
            "input keyevent $enter",
            "cmd input keyevent $enter",
            "input keyevent 66",
            "cmd input keyevent 66",
            "toybox input keyevent 66",
            "input keyevent KEYCODE_ENTER",
        )
        for (cmd in attempts) {
            LogBuffer.i("Shizuku", "try: $cmd")
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "  exit=$code out=${out.take(80).ifBlank { "(empty)" }}")
            if (code == 0) {
                LogBuffer.i("Shizuku", "Enter OK via $cmd")
                return true
            }
        }
        LogBuffer.w("Shizuku", "all Enter strategies failed — ${statusLine()}")
        return false
    }'''
    t = must_replace(t, old_enter, new_enter, "pressEnter")
    t = t.replace(
        '?: return -1 to "newProcess null — Shizuku API blocked?"',
        '?: return -1 to "newProcess null — Shizuku API blocked? grant CWBridge + restart Shizuku"',
        1,
    )
    p.write_text(t)
    print("OK ShizukuShell")


def patch_readme() -> None:
    r = ROOT / "README.md"
    if not r.exists():
        return
    t = r.read_text()
    t = t.replace("port `8765`", "port `8080` (falls back to 8765, then 80)")
    t = t.replace("http://<device-ip>:8765", "http://<device-ip>:8080")
    t = t.replace("https://<device-ip>", "http://<device-ip>")
    r.write_text(t)
    print("OK README")


def main() -> None:
    patch_local_http_server()
    patch_main_activity()
    patch_bridge_control()
    patch_shizuku()
    patch_readme()
    print("all patches applied")


if __name__ == "__main__":
    main()
