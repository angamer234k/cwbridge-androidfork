#!/usr/bin/env python3
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"

# Restore full file from last known-good commit (before truncation)
url = (
    "https://raw.githubusercontent.com/angamer234k/cwbridge-androidfork/"
    "3ca6b3b7039acd76d2101c34229e4117a2153ec8/"
    "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
)
t = urllib.request.urlopen(url, timeout=90).read().decode()
if "fun openDomains" not in t or len(t) < 10000:
    raise SystemExit("restore source invalid")

if "import android.util.Base64" not in t:
    t = t.replace("import android.os.Build", "import android.os.Build\nimport android.util.Base64", 1)

start = t.find("    private fun screencapPngBytes")
end = t.find("    private fun takeScreenshotAsync", start)
if start < 0 or end < 0:
    raise SystemExit("markers missing")

new = (
    "    private fun screencapPngBytes(context: Context): ByteArray? {\n"
    "        val attempts = listOf(\n"
    '            "screencap -p 2>/dev/null | base64",\n'
    '            "screencap -p /data/local/tmp/cwbridge_cap.png && base64 /data/local/tmp/cwbridge_cap.png && rm -f /data/local/tmp/cwbridge_cap.png",\n'
    '            "screencap -p /sdcard/cwbridge_cap.png && base64 /sdcard/cwbridge_cap.png && rm -f /sdcard/cwbridge_cap.png",\n'
    "        )\n"
    "        for (cmd in attempts) {\n"
    "            try {\n"
    "                val (code, out) = ShizukuShell.exec(cmd)\n"
    '                LogBuffer.i("Control", "screencap try exit=$code outLen=${out.length} cmd=${cmd.take(40)}")\n'
    "                if (code != 0 || out.isBlank()) continue\n"
    '                val cleaned = out.replace("\\n", "").replace("\\r", "").replace(" ", "")\n'
    "                val filtered = cleaned.filter {\n"
    "                    it.isLetterOrDigit() || it == '+' || it == '/' || it == '='\n"
    "                }\n"
    "                if (filtered.length < 200) continue\n"
    "                val bytes = Base64.decode(filtered, Base64.DEFAULT)\n"
    "                if (bytes.size > 100 && isPng(bytes)) {\n"
    '                    LogBuffer.i("Control", "screencap ok ${bytes.size} bytes via shell")\n'
    "                    return bytes\n"
    "                }\n"
    "                if (bytes.size > 500) {\n"
    '                    LogBuffer.i("Control", "screencap ok ${bytes.size} bytes (no png magic)")\n'
    "                    return bytes\n"
    "                }\n"
    "            } catch (t: Throwable) {\n"
    '                LogBuffer.w("Control", "screencap attempt: ${t.message}")\n'
    "            }\n"
    "        }\n"
    "        return try {\n"
    "            val dir = context.getExternalFilesDir(null) ?: context.cacheDir\n"
    '            val file = File(dir, "cw_screencap_web.png")\n'
    "            if (file.exists()) file.delete()\n"
    "            val path = file.absolutePath\n"
    '            val (code, out) = ShizukuShell.exec("screencap -p \\"$path\\" && chmod 644 \\"$path\\"")\n'
    "            LogBuffer.i(\n"
    '                "Control",\n'
    '                "screencap appdir exit=$code exists=${file.exists()} size=${file.length()} ${out.take(60)}",\n'
    "            )\n"
    "            if (code != 0 || !file.exists() || file.length() < 100L) null\n"
    "            else file.readBytes()\n"
    "        } catch (t: Throwable) {\n"
    '            LogBuffer.w("Control", "screencap appdir: ${t.message}")\n'
    "            null\n"
    "        }\n"
    "    }\n"
    "\n"
    "    private fun isPng(bytes: ByteArray): Boolean {\n"
    "        if (bytes.size < 8) return false\n"
    "        return bytes[0] == 0x89.toByte() &&\n"
    "            bytes[1] == 0x50.toByte() &&\n"
    "            bytes[2] == 0x4E.toByte() &&\n"
    "            bytes[3] == 0x47.toByte()\n"
    "    }\n"
    "\n"
)

t = t[:start] + new + t[end:]
# dedupe isPng
first = t.find("private fun isPng")
second = t.find("private fun isPng", first + 1)
if second > 0:
    third = t.find("\n    private fun ", second + 1)
    if third > 0:
        t = t[:second] + t[third + 1 :]  # keep newline via slice carefully
        # better:

p.write_text(t)
print("BridgeControl restored", p.stat().st_size, "openDomains", "openDomains" in t)
print("cleaned", [l for l in t.splitlines() if "val cleaned" in l][:1])
