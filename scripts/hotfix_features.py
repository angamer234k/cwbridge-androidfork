#!/usr/bin/env python3
"""Shizuku-first screenshots + remote control stream (poll + tap/hold on image)."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_bridge_control():
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = p.read_text()
    if "screencapPngBytes" in t:
        print("BridgeControl: already shizuku-first")
        return

    if "import java.io.File" not in t:
        t = t.replace(
            "import java.io.ByteArrayOutputStream",
            "import java.io.ByteArrayOutputStream\nimport java.io.File",
            1,
        )

    old = """    fun takeScreenshot(): Result<ByteArray> {
        val svc = TapService.instance
            ?: return Result.failure(IllegalStateException("CWBridge Tap (accessibility) is not connected"))
        if (!screenshotSupported()) {
            return Result.failure(
                UnsupportedOperationException("screenshot needs Android 11 (API 30)+"),
            )
        }
        return takeScreenshotAsync(svc)
    }"""

    new = """    /**
     * Prefer Shizuku `screencap` (works on many FLAG_SECURE surfaces like Roblox).
     * Fall back to AccessibilityService.takeScreenshot (Android 11+).
     */
    fun takeScreenshot(context: Context? = null): Result<ByteArray> {
        if (context != null && ShizukuShell.isReady()) {
            val bytes = screencapPngBytes(context)
            if (bytes != null) {
                LogBuffer.i("Control", "screenshot via Shizuku screencap (${bytes.size} bytes)")
                return Result.success(bytes)
            }
            LogBuffer.w("Control", "screencap failed — trying a11y")
        }
        val svc = TapService.instance
        if (svc != null && screenshotSupported()) {
            return takeScreenshotAsync(svc)
        }
        if (svc == null) {
            return Result.failure(
                IllegalStateException(
                    "screenshot failed: need Shizuku (screencap) or CWBridge Tap accessibility",
                ),
            )
        }
        return Result.failure(
            UnsupportedOperationException("screenshot needs Android 11 (API 30)+ or Shizuku"),
        )
    }

    private fun screencapPngBytes(context: Context): ByteArray? {
        return try {
            val dir = context.getExternalFilesDir(null) ?: context.cacheDir
            val file = File(dir, "cw_screencap_web.png")
            if (file.exists()) file.delete()
            val path = file.absolutePath
            val (code, out) = ShizukuShell.exec("screencap -p \\\"$path\\\" && chmod 644 \\\"$path\\\"")
            LogBuffer.i(
                "Control",
                "screencap exit=$code exists=${file.exists()} size=${file.length()} ${out.take(60)}",
            )
            if (code != 0 || !file.exists() || file.length() < 100L) return null
            file.readBytes()
        } catch (t: Throwable) {
            LogBuffer.w("Control", "screencap: ${t.message}")
            null
        }
    }"""

    if old not in t:
        raise SystemExit("takeScreenshot block not found")
    t = t.replace(old, new, 1)
    p.write_text(t)
    print("BridgeControl: shizuku-first screenshot")


def patch_server():
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()

    t2 = t.replace(
        "val shot = BridgeControl.takeScreenshot()",
        "val shot = BridgeControl.takeScreenshot(context)",
    )
    if t2 != t:
        t = t2
        print("Server: takeScreenshot(context)")
    else:
        print("Server: takeScreenshot call already patched or miss")

    if "holdMs" not in t:
        import re
        m = re.search(r"private fun tapJson\(body: String\): String \{[\s\S]*?\n    \}\n", t)
        if not m:
            print("Server: tapJson not found")
        else:
            new_tap = """    private fun tapJson(body: String): String {
        val svc = TapService.instance
            ?: return json(mapOf("error" to "CWBridge Tap (accessibility) not connected"))
        val holdMs = (jsonDouble(body, "holdMs") ?: 0.0).toLong().coerceIn(0L, 5000L)
        val ok = when (jsonString(body, "mode")) {
            "percent" -> {
                val x = jsonDouble(body, "x") ?: return json(mapOf("error" to "x and y required"))
                val y = jsonDouble(body, "y") ?: return json(mapOf("error" to "x and y required"))
                if (holdMs > 50L) svc.longPressPercent(x.toFloat(), y.toFloat(), holdMs)
                else svc.clickAtPercent(x.toFloat(), y.toFloat())
            }
            "px" -> {
                val x = jsonDouble(body, "x") ?: return json(mapOf("error" to "x and y required"))
                val y = jsonDouble(body, "y") ?: return json(mapOf("error" to "x and y required"))
                if (holdMs > 50L) svc.longPress(x.toFloat(), y.toFloat(), holdMs)
                else svc.clickAt(x.toFloat(), y.toFloat())
            }
            else -> return json(mapOf("error" to "mode percent|px"))
        }
        return json(mapOf("ok" to ok, "holdMs" to holdMs))
    }
"""
            t = t[: m.start()] + new_tap + t[m.end() :]
            print("Server: tapJson holdMs")

    t = t.replace(
        '"supported (FLAG_SECURE games still fail)"',
        '"supported (Shizuku screencap preferred; a11y fallback)"',
    )

    p.write_text(t)
    print("LocalHttpServer", p.stat().st_size)


def patch_tapservice():
    p = ROOT / "app/src/main/java/com/cwbridge/android/TapService.kt"
    t = p.read_text()
    if "fun longPressPercent" in t:
        print("TapService: longPress already")
        return
    insert_after = 'fun clickAt(x: Float, y: Float): Boolean = gestureTap(x, y, "px")'
    if insert_after not in t:
        raise SystemExit("clickAt missing")
    methods = '''
    fun longPress(x: Float, y: Float, holdMs: Long = 600L): Boolean =
        gestureHold(x, y, holdMs.coerceIn(80L, 5000L), "px-hold")

    fun longPressPercent(xPercent: Float, yPercent: Float, holdMs: Long = 600L): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * (xPercent / 100f)
        val y = dm.heightPixels * (yPercent / 100f)
        return longPress(x, y, holdMs)
    }

    private fun gestureHold(x: Float, y: Float, holdMs: Long, tag: String): Boolean {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, holdMs)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        LogBuffer.i("A11y", "GESTURE_HOLD $tag at=(${x.toInt()},${y.toInt()}) ms=$holdMs ok=$ok")
        return ok
    }

'''
    t = t.replace(insert_after, insert_after + "\n" + methods, 1)
    p.write_text(t)
    print("TapService: longPress")


def patch_webui():
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = p.read_text()

    old_card = """  <div class=\"card\" id=\"sec-control\">
    <h2><span class=\"ms sm\">tune</span> Remote control</h2>
    <div class=\"row\">
      <button type=\"button\" id=\"btnRestartBridge\" onclick=\"ctlBusy(this,'restart-bridge')\"><span class=\"ms sm\">restart_alt</span> Restart bridge</button>
      <button type=\"button\" id=\"btnRestartRoblox\" onclick=\"ctlBusy(this,'restart-roblox')\"><span class=\"ms sm\">sports_esports</span> Restart Roblox</button>
      <button type=\"button\" onclick=\"toggleBridge()\"><span class=\"ms sm\">power_settings_new</span> Toggle bridge</button>
      <!--SCREENSHOT_BUTTON-->
    </div>
    <div id=\"ctlMsg\" class=\"msg\"></div>
    <div id=\"shotBox\"></div>
  </div>"""

    # Use unescaped form matching file
    old_card = """  <div class="card" id="sec-control">
    <h2><span class="ms sm">tune</span> Remote control</h2>
    <div class="row">
      <button type="button" id="btnRestartBridge" onclick="ctlBusy(this,'restart-bridge')"><span class="ms sm">restart_alt</span> Restart bridge</button>
      <button type="button" id="btnRestartRoblox" onclick="ctlBusy(this,'restart-roblox')"><span class="ms sm">sports_esports</span> Restart Roblox</button>
      <button type="button" onclick="toggleBridge()"><span class="ms sm">power_settings_new</span> Toggle bridge</button>
      <!--SCREENSHOT_BUTTON-->
    </div>
    <div id="ctlMsg" class="msg"></div>
    <div id="shotBox"></div>
  </div>"""

    new_card = """  <div class="card" id="sec-control">
    <h2><span class="ms sm">tune</span> Remote control</h2>
    <p class="hint">Screenshot prefers Shizuku screencap (sees Roblox). Stream: live view — tap image to tap device; hold ~0.5s for long-press.</p>
    <div class="row">
      <button type="button" id="btnRestartBridge" onclick="ctlBusy(this,'restart-bridge')"><span class="ms sm">restart_alt</span> Restart bridge</button>
      <button type="button" id="btnRestartRoblox" onclick="ctlBusy(this,'restart-roblox')"><span class="ms sm">sports_esports</span> Restart Roblox</button>
      <button type="button" onclick="toggleBridge()"><span class="ms sm">power_settings_new</span> Toggle bridge</button>
      <!--SCREENSHOT_BUTTON-->
      <button type="button" id="btnStream" onclick="toggleStream()"><span class="ms sm">live_tv</span> Start stream</button>
    </div>
    <div id="ctlMsg" class="msg"></div>
    <div id="shotBox"></div>
  </div>"""

    if old_card in t:
        t = t.replace(old_card, new_card, 1)
        print("WebUi: control card stream")
    elif "toggleStream" in t:
        print("WebUi: stream already")
    else:
        print("WebUi: control card pattern miss")

    if "img#shotImg{" not in t:
        style_add = """
img#shotImg{max-width:100%;height:auto;border-radius:10px;border:1px solid var(--line);cursor:crosshair;touch-action:none;user-select:none}
.stream-on #btnStream{background:var(--good)}
"""
        t = t.replace("</style>", style_add + "</style>", 1)
        print("WebUi: shotImg css")

    if "async function loadShot()" in t and "toggleStream" not in t:
        stream_js = r'''
var streamTimer=null;
var streamOn=false;
var pressStart=0;
function toggleStream(){
  if(streamOn){ stopStream(); }
  else { startStream(); }
}
function startStream(){
  streamOn=true;
  document.body.classList.add('stream-on');
  var b=document.getElementById('btnStream');
  if(b) b.innerHTML='<span class="ms sm">stop_circle</span> Stop stream';
  msg('ctlMsg','stream on — tap image to control','good');
  refreshShot(true);
  streamTimer=setInterval(function(){ refreshShot(false); }, 1200);
}
function stopStream(){
  streamOn=false;
  document.body.classList.remove('stream-on');
  var b=document.getElementById('btnStream');
  if(b) b.innerHTML='<span class="ms sm">live_tv</span> Start stream';
  if(streamTimer){ clearInterval(streamTimer); streamTimer=null; }
  msg('ctlMsg','stream off','');
}
async function refreshShot(announce){
  try{
    var res=await api('/api/screenshot');
    if(!res.ok){
      var err=await res.json().catch(function(){return {};});
      if(announce) msg('ctlMsg', err.error||('HTTP '+res.status), 'err');
      return;
    }
    var blob=await res.blob();
    var url=URL.createObjectURL(blob);
    var box=D('shotBox');
    var img=document.getElementById('shotImg');
    if(img){
      var old=img.src;
      img.src=url;
      if(old && old.indexOf('blob:')===0) try{ URL.revokeObjectURL(old);}catch(e){}
    } else {
      var ts=new Date().toISOString().replace(/[:.]/g,'-');
      box.innerHTML='<img id="shotImg" alt="screenshot" src="'+url+'">'+ 
        '<div class="row" style="margin-top:8px">'+ 
        '<a class="btn ghost" download="cwbridge-'+ts+'.png" href="'+url+'">'+ 
        '<span class="ms sm">download</span> Download</a></div>';
      bindShotInput();
    }
    if(announce) msg('ctlMsg','screenshot ok','good');
  }catch(e){
    if(announce) msg('ctlMsg', e.message||String(e), 'err');
  }
}
function bindShotInput(){
  var img=document.getElementById('shotImg');
  if(!img || img._bound) return;
  img._bound=true;
  img.addEventListener('pointerdown', function(ev){
    ev.preventDefault();
    pressStart=Date.now();
    img.setPointerCapture(ev.pointerId);
  });
  img.addEventListener('pointerup', function(ev){
    ev.preventDefault();
    var held=Date.now()-pressStart;
    var rect=img.getBoundingClientRect();
    var px=((ev.clientX-rect.left)/rect.width)*100;
    var py=((ev.clientY-rect.top)/rect.height)*100;
    px=Math.max(0,Math.min(100,px));
    py=Math.max(0,Math.min(100,py));
    var holdMs=held>=450?Math.min(2000,held):0;
    sendRemoteTap(px,py,holdMs);
  });
}
async function sendRemoteTap(x,y,holdMs){
  try{
    var j=await post('/api/tap',{mode:'percent',x:x,y:y,holdMs:holdMs||0});
    if(j.error){ msg('ctlMsg', j.error, 'err'); toast(j.error,false); return; }
    msg('ctlMsg', (holdMs?'hold ':'tap ')+x.toFixed(1)+'% '+y.toFixed(1)+'%', 'good');
  }catch(e){ msg('ctlMsg', e.message||String(e), 'err'); }
}
async function loadShot(){
  await refreshShot(true);
  bindShotInput();
'''
        idx = t.find("async function loadShot()")
        rest = t[idx:]
        end_rel = rest.find("\nasync function ", 10)
        end_rel2 = rest.find("\nfunction ", 10)
        candidates = [c for c in [end_rel, end_rel2] if c > 0]
        if candidates:
            end = min(candidates)
            t = t[:idx] + stream_js + rest[end:]
            print("WebUi: loadShot + stream JS")
        else:
            print("WebUi: cannot find end of loadShot")
    elif "toggleStream" in t:
        print("WebUi: stream JS already")

    p.write_text(t)
    print("WebUi", p.stat().st_size)


def main():
    patch_bridge_control()
    patch_server()
    patch_tapservice()
    patch_webui()
    print("hotfix remote OK")


if __name__ == "__main__":
    main()
