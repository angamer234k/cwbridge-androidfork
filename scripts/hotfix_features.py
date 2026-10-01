#!/usr/bin/env python3
"""Screenshot strip bug: ShizukuShell.exec truncated stdout to 2000 chars."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_shizuku():
    p = ROOT / "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"
    t = p.read_text()
    if "maxOut: Int" in t or "maxOutChars" in t:
        print("ShizukuShell: already has maxOut")
        return

    old = "    fun exec(command: String): Pair<Int, String> {"
    new = "    fun exec(command: String, maxOut: Int = 2000): Pair<Int, String> {"
    if old not in t:
        raise SystemExit("exec signature not found")
    t = t.replace(old, new, 1)

    old_take = "code to out.toString().trim().take(2000)"
    new_take = "code to out.toString().trim().let { if (maxOut <= 0) it else it.take(maxOut) }"
    if old_take not in t:
        raise SystemExit("take(2000) not found")
    t = t.replace(old_take, new_take, 1)

    # longer join for large base64 drains
    t = t.replace(
        "readerThread.join(2000)\n                errThread.join(500)",
        "readerThread.join(if (maxOut <= 0 || maxOut > 50_000) 30_000L else 2000L)\n"
        "                errThread.join(if (maxOut <= 0 || maxOut > 50_000) 5_000L else 500L)",
        1,
    )

    p.write_text(t)
    print("ShizukuShell: maxOut param")


def patch_bridge():
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = p.read_text()

    # Use unlimited/large maxOut for base64 screencap paths
    # Prefer tmp file then copy into app dir (avoids multi-MB strings when possible)
    start = t.find("    private fun screencapPngBytes")
    end = t.find("    private fun takeScreenshotAsync", start)
    if start < 0 or end < 0:
        raise SystemExit("screencap markers missing")

    new = r'''    private fun screencapPngBytes(context: Context): ByteArray? {
        // Prefer write to tmp + copy into app-readable path (full binary, no 2KB truncate).
        val dir = context.getExternalFilesDir(null) ?: context.cacheDir
        val dest = File(dir, "cw_screencap_web.png")
        try {
            if (dest.exists()) dest.delete()
        } catch (_: Throwable) {
        }
        val destPath = dest.absolutePath
        val tmp = "/data/local/tmp/cwbridge_cap.png"

        // Shell writes PNG, copies into our externalFilesDir, chmod so app can read.
        val copyCmds = listOf(
            "screencap -p $tmp && cp -f $tmp \"$destPath\" && chmod 644 \"$destPath\" && rm -f $tmp",
            "screencap -p /sdcard/cwbridge_cap.png && cp -f /sdcard/cwbridge_cap.png \"$destPath\" && chmod 644 \"$destPath\"",
            "screencap -p \"$destPath\" && chmod 644 \"$destPath\"",
        )
        for (cmd in copyCmds) {
            try {
                val (code, out) = ShizukuShell.exec(cmd, maxOut = 4000)
                LogBuffer.i(
                    "Control",
                    "screencap file exit=$code exists=${dest.exists()} size=${dest.length()} ${out.take(80)}",
                )
                if (code == 0 && dest.exists() && dest.length() > 1000L) {
                    val bytes = dest.readBytes()
                    if (isPng(bytes)) {
                        LogBuffer.i("Control", "screencap ok ${bytes.size} bytes via file")
                        return bytes
                    }
                    if (bytes.size > 5000) {
                        LogBuffer.i("Control", "screencap ok ${bytes.size} bytes file (no magic)")
                        return bytes
                    }
                }
            } catch (t: Throwable) {
                LogBuffer.w("Control", "screencap file: ${t.message}")
            }
        }

        // Fallback: base64 over stdout — needs large maxOut (old default was 2000 = thin strip)
        val b64Cmds = listOf(
            "screencap -p $tmp && base64 $tmp && rm -f $tmp",
            "screencap -p 2>/dev/null | base64",
        )
        for (cmd in b64Cmds) {
            try {
                val (code, out) = ShizukuShell.exec(cmd, maxOut = 0) // 0 = no truncate
                LogBuffer.i("Control", "screencap b64 exit=$code outLen=${out.length}")
                if (code != 0 || out.length < 500) continue
                val cleaned = out.replace("\n", "").replace("\r", "").replace(" ", "")
                val filtered = cleaned.filter {
                    it.isLetterOrDigit() || it == '+' || it == '/' || it == '='
                }
                if (filtered.length < 500) continue
                val bytes = Base64.decode(filtered, Base64.DEFAULT)
                if (bytes.size > 1000 && isPng(bytes)) {
                    LogBuffer.i("Control", "screencap ok ${bytes.size} bytes via b64")
                    return bytes
                }
                if (bytes.size > 5000) {
                    LogBuffer.i("Control", "screencap ok ${bytes.size} bytes b64 (no magic)")
                    return bytes
                }
            } catch (t: Throwable) {
                LogBuffer.w("Control", "screencap b64: ${t.message}")
            }
        }
        return null
    }

'''

    # Keep existing isPng if present between screencap and takeScreenshotAsync
    mid = t[start:end]
    if "private fun isPng" in mid:
        ispng_start = mid.find("    private fun isPng")
        # include isPng from original mid after our new body
        ispng = mid[ispng_start:]
        t = t[:start] + new + ispng + t[end:]
    else:
        ispng = '''    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
    }

'''
        t = t[:start] + new + ispng + t[end:]

    p.write_text(t)
    print("BridgeControl screencap full-frame", p.stat().st_size)


def main():
    patch_shizuku()
    patch_bridge()
    print("hotfix full-frame OK")


if __name__ == "__main__":
    main()
