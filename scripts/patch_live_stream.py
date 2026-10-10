#!/usr/bin/env python3
"""Apply live-stream patches to BridgeControl, LocalHttpServer, WebUi."""
from pathlib import Path


def patch_bridge_control():
    p = Path("app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt")
    t = p.read_text()
    if "fun pngToJpeg" in t:
        print("BridgeControl: already has pngToJpeg")
        return
    if "android.graphics.BitmapFactory" not in t:
        t = t.replace(
            "import android.graphics.Bitmap\n",
            "import android.graphics.Bitmap\nimport android.graphics.BitmapFactory\n",
        )
    needle = (
        "    private fun encodePng(bitmap: Bitmap): Result<ByteArray> = try {\n"
        "        val out = ByteArrayOutputStream()\n"
        "        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)\n"
        "        Result.success(out.toByteArray())\n"
        "    } catch (t: Throwable) {\n"
        "        Result.failure(IllegalStateException(\"png encode failed: ${t.message}\"))\n"
        "    }"
    )
    insert = needle + (
        "\n\n"
        "    /** Re-encode PNG bytes as JPEG for faster live-stream frames. */\n"
        "    fun pngToJpeg(png: ByteArray, quality: Int = 55): ByteArray? {\n"
        "        return try {\n"
        "            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null\n"
        "            val out = ByteArrayOutputStream()\n"
        "            val q = quality.coerceIn(20, 95)\n"
        "            if (!bmp.compress(Bitmap.CompressFormat.JPEG, q, out)) return null\n"
        "            out.toByteArray()\n"
        "        } catch (_: Throwable) {\n"
        "            null\n"
        "        }\n"
        "    }"
    )
    if needle not in t:
        raise SystemExit("BridgeControl: encodePng block not found")
    p.write_text(t.replace(needle, insert))
    print("BridgeControl: pngToJpeg added", p.stat().st_size)


def patch_local_http():
    p = Path("app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt")
    t = p.read_text()
    if "wantJpeg" in t:
        print("LocalHttpServer: already jpeg-aware")
        return
    old = (
        '            path == "/api/screenshot" -> {\n'
        "                val shot = BridgeControl.takeScreenshot(context)\n"
        "                shot.fold(\n"
        "                    onSuccess = { bytes ->\n"
        '                        respondBytes(out, 200, bytes, "image/png")\n'
        "                    },\n"
        "                    onFailure = {\n"
        "                        val code = if (it is UnsupportedOperationException) 501 else 503\n"
        '                        respond(out, code, json(mapOf("error" to (it.message ?: "failed"))))\n'
        "                    },\n"
        "                )\n"
        "            }"
    )
    new = (
        '            path == "/api/screenshot" -> {\n'
        "                val shot = BridgeControl.takeScreenshot(context)\n"
        '                val wantJpeg = query["fmt"]?.equals("jpeg", ignoreCase = true) == true ||\n'
        '                    query["format"]?.equals("jpeg", ignoreCase = true) == true\n'
        '                val quality = query["q"]?.toIntOrNull()?.coerceIn(20, 95) ?: 55\n'
        "                shot.fold(\n"
        "                    onSuccess = { bytes ->\n"
        "                        if (wantJpeg) {\n"
        "                            val jpg = BridgeControl.pngToJpeg(bytes, quality)\n"
        '                            if (jpg != null) respondBytes(out, 200, jpg, "image/jpeg")\n'
        '                            else respondBytes(out, 200, bytes, "image/png")\n'
        "                        } else {\n"
        '                            respondBytes(out, 200, bytes, "image/png")\n'
        "                        }\n"
        "                    },\n"
        "                    onFailure = {\n"
        "                        val code = if (it is UnsupportedOperationException) 501 else 503\n"
        '                        respond(out, code, json(mapOf("error" to (it.message ?: "failed"))))\n'
        "                    },\n"
        "                )\n"
        "            }"
    )
    if old not in t:
        raise SystemExit("LocalHttpServer: screenshot block not found")
    p.write_text(t.replace(old, new))
    print("LocalHttpServer: jpeg query added", p.stat().st_size)


def patch_webui():
    p = Path("app/src/main/java/com/cwbridge/android/server/WebUi.kt")
    t = p.read_text()

    old_btn = (
        '<button type="button" id="btnStream" '
        "onclick=\"msg('ctlMsg','stream coming next build','')\">"
        '<span class="ms sm">live_tv</span> Start stream</button>'
    )
    new_btn = (
        '<button type="button" id="btnStream" onclick="toggleStream()">'
        '<span class="ms sm">live_tv</span> Start stream</button>'
    )
    if old_btn in t:
        t = t.replace(old_btn, new_btn)
        print("WebUi: button wired")
    elif 'onclick="toggleStream()"' in t:
        print("WebUi: button already wired")
    else:
        raise SystemExit("WebUi: btnStream not found")

    if "#streamImg" not in t:
        t = t.replace(
            ".stream-on #btnStream{background:var(--good)}",
            ".stream-on #btnStream{background:var(--good)}\n"
            "#streamImg,#shotImg{cursor:crosshair;width:100%;max-width:100%;"
            "display:block;touch-action:none;user-select:none}\n"
            "#shotBox{margin-top:10px}",
        )

    if "function toggleStream" not in t:
        # Use document.getElementById for dynamic streamImg (not static D() ids)
        js = r'''
var streamOn = false;
var streamTimer = null;

function toggleStream(){
  if(streamOn) stopStream();
  else startStream();
}
function startStream(){
  streamOn = true;
  try{ document.body.classList.add("stream-on"); }catch(e){}
  var btn = D("btnStream");
  if(btn) btn.innerHTML = "<span class=\"ms sm\">stop_circle</span> Stop stream";
  msg("ctlMsg", "streaming…", "good");
  pollStream();
}
function stopStream(){
  streamOn = false;
  try{ document.body.classList.remove("stream-on"); }catch(e){}
  if(streamTimer){ clearTimeout(streamTimer); streamTimer = null; }
  var btn = D("btnStream");
  if(btn) btn.innerHTML = "<span class=\"ms sm\">live_tv</span> Start stream";
  msg("ctlMsg", "stream stopped", "");
}
async function pollStream(){
  if(!streamOn) return;
  try{
    var res = await fetch("/api/screenshot?fmt=jpeg&q=50", {credentials:"same-origin", cache:"no-store"});
    if(res.status === 401){ location.reload(); return; }
    if(res.ok){
      var blob = await res.blob();
      if(blob && blob.size > 100){
        var url = URL.createObjectURL(blob);
        var box = D("shotBox");
        var img = document.getElementById("streamImg");
        if(!img && box){
          box.innerHTML = "<img id=\"streamImg\" alt=\"live\">";
          img = document.getElementById("streamImg");
          if(img) bindStreamTap(img);
        }
        if(img){
          var old = img.src;
          img.src = url;
          if(old && old.indexOf("blob:") === 0){ try{ URL.revokeObjectURL(old); }catch(e){} }
        }
      }
    }
  }catch(e){}
  if(streamOn) streamTimer = setTimeout(pollStream, 400);
}
function bindStreamTap(img){
  var downAt = 0, downX = 0, downY = 0;
  function pct(ev){
    var r = img.getBoundingClientRect();
    var src = (ev.changedTouches && ev.changedTouches[0]) || ev;
    var cx = src.clientX, cy = src.clientY;
    var x = ((cx - r.left) / Math.max(1, r.width)) * 100;
    var y = ((cy - r.top) / Math.max(1, r.height)) * 100;
    return {x: Math.max(0, Math.min(100, x)), y: Math.max(0, Math.min(100, y))};
  }
  function onDown(ev){
    try{ ev.preventDefault(); }catch(e){}
    downAt = Date.now();
    var p = pct(ev);
    downX = p.x; downY = p.y;
  }
  function onUp(ev){
    try{ ev.preventDefault(); }catch(e){}
    if(!downAt) return;
    var held = Date.now() - downAt;
    downAt = 0;
    var body = {mode:"percent", x: downX, y: downY};
    if(held >= 450) body.holdMs = Math.min(held, 2000);
    post("/api/tap", body).then(function(j){
      if(j && j.error) msg("ctlMsg", j.error, "err");
      else msg("ctlMsg", (body.holdMs ? "long-press " : "tap ") + downX.toFixed(1) + "% " + downY.toFixed(1) + "%", "good");
    }).catch(function(e){ msg("ctlMsg", e.message || String(e), "err"); });
  }
  img.addEventListener("pointerdown", onDown);
  img.addEventListener("pointerup", onUp);
  img.addEventListener("pointercancel", function(){ downAt = 0; });
}

'''
        marker = "async function loadShot(){"
        if marker not in t:
            raise SystemExit("WebUi: loadShot not found")
        t = t.replace(marker, js + marker)
        print("WebUi: stream JS injected")

    old_shot = (
        "    D('shotBox').innerHTML =\n"
        "      '<img id=\"shotImg\" alt=\"screenshot\" src=\"' + url + '\">' +\n"
        "      '<div class=\"row\" style=\"margin-top:8px\">' +\n"
        "      '<a class=\"btn ghost\" download=\"cwbridge-' + ts + '.png\" href=\"' + url + '\">' +\n"
        "      '<span class=\"ms sm\">download</span> Download</a></div>';"
    )
    new_shot = old_shot + (
        "\n    var si = document.getElementById('shotImg');\n"
        "    if(si) bindStreamTap(si);"
    )
    if old_shot in t and "if(si) bindStreamTap(si)" not in t:
        t = t.replace(old_shot, new_shot)
        print("WebUi: loadShot tap bind added")

    p.write_text(t)
    print("WebUi size", p.stat().st_size)


if __name__ == "__main__":
    patch_bridge_control()
    patch_local_http()
    patch_webui()
    print("all patches applied")
