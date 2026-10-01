#!/usr/bin/env python3
"""Fix screenshot: Shizuku often cannot write app-private paths. Use tmp+base64."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_bridge():
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = p.read_text()

    if "import android.util.Base64" not in t:
        t = t.replace(
            "import android.os.Build",
            "import android.os.Build\nimport android.util.Base64",
            1,
        )

    old = None
    start = t.find("    private fun screencapPngBytes")
    if start < 0:
        raise SystemExit("screencapPngBytes missing")
    # end at next private fun or blank line before private fun takeScreenshotAsync
    end = t.find("    private fun takeScreenshotAsync", start)
    if end < 0:
        end = t.find("\n    private fun ", start + 20)
    if end < 0:
        raise SystemExit("end of screencapPngBytes miss")

    new = '''    private fun screencapPngBytes(context: Context): ByteArray? {
        // Shizuku runs as shell — often cannot write app-private externalFilesDir.
        // Prefer /data/local/tmp then base64 back to the app process.
        val attempts = listOf(
            // direct pipe (no intermediate file)
            "screencap -p 2>/dev/null | base64",
            // tmp file then base64
            "screencap -p /data/local/tmp/cwbridge_cap.png && base64 /data/local/tmp/cwbridge_cap.png && rm -f /data/local/tmp/cwbridge_cap.png",
            // sdcard fallback
            "screencap -p /sdcard/cwbridge_cap.png && base64 /sdcard/cwbridge_cap.png && rm -f /sdcard/cwbridge_cap.png",
        )
        for (cmd in attempts) {
            try {
                val (code, out) = ShizukuShell.exec(cmd)
                LogBuffer.i("Control", "screencap try exit=$code outLen=${out.length} cmd=${cmd.take(40)}")
                if (code != 0 || out.isBlank()) continue
                val cleaned = out.replace("\\n", "").replace("\\r", "").replace(" ", "")
                // keep only base64 alphabet (ignore shell noise)
                val filtered = cleaned.filter {
                    it.isLetterOrDigit() || it == '+' || it == '/' || it == '='
                }
                if (filtered.length < 200) continue
                val bytes = Base64.decode(filtered, Base64.DEFAULT)
                if (bytes != null && bytes.size > 100 && isPng(bytes)) {
                    LogBuffer.i("Control", "screencap ok ${bytes.size} bytes via shell")
                    return bytes
                }
                if (bytes != null && bytes.size > 500) {
                    // accept even if PNG magic check fails (some OEMs)
                    LogBuffer.i("Control", "screencap ok ${bytes.size} bytes (no png magic)")
                    return bytes
                }
            } catch (t: Throwable) {
                LogBuffer.w("Control", "screencap attempt: ${t.message}")
            }
        }

        // last resort: write into app dir (works on some devices)
        return try {
            val dir = context.getExternalFilesDir(null) ?: context.cacheDir
            val file = File(dir, "cw_screencap_web.png")
            if (file.exists()) file.delete()
            val path = file.absolutePath
            val (code, out) = ShizukuShell.exec("screencap -p \\"$path\\" && chmod 644 \\"$path\\"")
            LogBuffer.i(
                "Control",
                "screencap appdir exit=$code exists=${file.exists()} size=${file.length()} ${out.take(60)}",
            )
            if (code != 0 || !file.exists() || file.length() < 100L) null
            else file.readBytes()
        } catch (t: Throwable) {
            LogBuffer.w("Control", "screencap appdir: ${t.message}")
            null
        }
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
    }

'''

    # Fix the cleaned replace - in the actual Kotlin we need real newlines not \\n in replace string
    new = new.replace('out.replace("\\n", "").replace("\\r", "")', 'out.replace("\n", "").replace("\r", "")')

    t = t[:start] + new + t[end:]
    p.write_text(t)
    print("BridgeControl screencap hardened", p.stat().st_size)


def patch_webui():
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = p.read_text()
    old = '''async function loadShot(){
  msg("ctlMsg", "capturing...", "");
  try{
    var res = await api("/api/screenshot");
    if(!res.ok){
      var err = await res.json().catch(function(){return {};});
      msg("ctlMsg", err.error||("HTTP "+res.status), "err");
      return;
    }
    var blob = await res.blob();
    var url = URL.createObjectURL(blob);
    var ts = new Date().toISOString().replace(/[:.]/g, "-");
    D("shotBox").innerHTML =
      "<img id=\\"shotImg\\" alt=\\"screenshot\\" src=\\"" + url + "\\">" +
      "<div class=\\"row\\" style=\\"margin-top:8px\\">" +
      "<a class=\\"btn ghost\\" id=\\"shotDl\\" download=\\"cwbridge-" + ts + ".png\\" href=\\"" + url + "\\">" +
      "<span class=\\"ms sm\\">download</span> Download</a></div>";
    msg("ctlMsg", "screenshot ok", "good");
  }catch(e){ msg("ctlMsg", e.message, "err"); }
}'''
    # Match actual file (single backslash in kotlin source for JS quotes)
    old2 = '''async function loadShot(){
  msg("ctlMsg", "capturing...", "");
  try{
    var res = await api("/api/screenshot");
    if(!res.ok){
      var err = await res.json().catch(function(){return {};});
      msg("ctlMsg", err.error||("HTTP "+res.status), "err");
      return;
    }
    var blob = await res.blob();
    var url = URL.createObjectURL(blob);
    var ts = new Date().toISOString().replace(/[:.]/g, "-");
    D("shotBox").innerHTML =
      "<img id=\"shotImg\" alt=\"screenshot\" src=\"" + url + "\">" +
      "<div class=\"row\" style=\"margin-top:8px\">" +
      "<a class=\"btn ghost\" id=\"shotDl\" download=\"cwbridge-" + ts + ".png\" href=\"" + url + "\">" +
      "<span class=\"ms sm\">download</span> Download</a></div>";
    msg("ctlMsg", "screenshot ok", "good");
  }catch(e){ msg("ctlMsg", e.message, "err"); }
}'''

    new_js = '''async function loadShot(){
  msg("ctlMsg", "capturing...", "");
  try{
    var res = await fetch("/api/screenshot", {credentials:"same-origin"});
    if(res.status === 401){ location.reload(); return; }
    var ct = res.headers.get("content-type") || "";
    if(!res.ok){
      var errMsg = "HTTP " + res.status;
      if(ct.indexOf("json") >= 0){
        try{ var j = await res.json(); errMsg = j.error || j.message || errMsg; }catch(e){}
      } else {
        try{ errMsg = (await res.text()).slice(0, 180) || errMsg; }catch(e){}
      }
      msg("ctlMsg", errMsg, "err");
      toast(errMsg, false);
      return;
    }
    var blob = await res.blob();
    if(!blob || blob.size < 100){
      msg("ctlMsg", "empty screenshot ("+(blob&&blob.size||0)+" bytes)", "err");
      return;
    }
    var url = URL.createObjectURL(blob);
    var ts = new Date().toISOString().replace(/[:.]/g, "-");
    D("shotBox").innerHTML =
      "<img id=\"shotImg\" alt=\"screenshot\" src=\"" + url + "\">" +
      "<div class=\"row\" style=\"margin-top:8px\">" +
      "<a class=\"btn ghost\" download=\"cwbridge-" + ts + ".png\" href=\"" + url + "\">" +
      "<span class=\"ms sm\">download</span> Download</a></div>";
    msg("ctlMsg", "screenshot ok (" + Math.round(blob.size/1024) + " KB)", "good");
  }catch(e){ msg("ctlMsg", e.message||String(e), "err"); toast(e.message||String(e), false); }
}'''

    if old2 in t:
        t = t.replace(old2, new_js, 1)
        print("WebUi loadShot improved")
    elif "fetch(\"/api/screenshot\"" in t or 'fetch("/api/screenshot"' in t:
        print("WebUi loadShot already fetch")
    else:
        idx = t.find("async function loadShot()")
        if idx < 0:
            print("WebUi loadShot miss")
        else:
            end = t.find("\nasync function ", idx + 10)
            if end < 0:
                end = t.find("\nfunction ", idx + 10)
            if end > 0:
                t = t[:idx] + new_js + "\n" + t[end:]
                print("WebUi loadShot replaced by span")
            else:
                print("WebUi loadShot end miss")

    p.write_text(t)
    print("WebUi", p.stat().st_size)


def main():
    patch_bridge()
    patch_webui()
    print("hotfix screencap OK")


if __name__ == "__main__":
    main()
