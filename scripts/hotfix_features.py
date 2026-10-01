#!/usr/bin/env python3
from pathlib import Path

p = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
t = p.read_text()

idx = t.find("async function loadShot()")
if idx < 0:
    raise SystemExit("loadShot missing")
end = t.find("\nasync function ", idx + 10)
if end < 0:
    end = t.find("\nfunction ", idx + 10)
if end < 0:
    raise SystemExit("loadShot end missing")

# Use single-quoted JS strings so Kotlin \" mess never happens
new_js = r'''async function loadShot(){
  msg('ctlMsg', 'capturing...', '');
  try{
    var res = await fetch('/api/screenshot', {credentials:'same-origin'});
    if(res.status === 401){ location.reload(); return; }
    var ct = res.headers.get('content-type') || '';
    if(!res.ok){
      var errMsg = 'HTTP ' + res.status;
      if(ct.indexOf('json') >= 0){
        try{ var j = await res.json(); errMsg = j.error || j.message || errMsg; }catch(e){}
      } else {
        try{ errMsg = (await res.text()).slice(0, 180) || errMsg; }catch(e){}
      }
      msg('ctlMsg', errMsg, 'err');
      try{ toast(errMsg, false); }catch(e){}
      return;
    }
    var blob = await res.blob();
    if(!blob || blob.size < 100){
      msg('ctlMsg', 'empty screenshot ('+(blob&&blob.size||0)+' bytes)', 'err');
      return;
    }
    var url = URL.createObjectURL(blob);
    var ts = new Date().toISOString().replace(/[:.]/g, '-');
    D('shotBox').innerHTML =
      '<img id="shotImg" alt="screenshot" src="' + url + '">' +
      '<div class="row" style="margin-top:8px">' +
      '<a class="btn ghost" download="cwbridge-' + ts + '.png" href="' + url + '">' +
      '<span class="ms sm">download</span> Download</a></div>';
    msg('ctlMsg', 'screenshot ok (' + Math.round(blob.size/1024) + ' KB)', 'good');
  }catch(e){
    msg('ctlMsg', e.message||String(e), 'err');
    try{ toast(e.message||String(e), false); }catch(x){}
  }
}

'''

t = t[:idx] + new_js + t[end:]
p.write_text(t)
print('WebUi loadShot fixed', p.stat().st_size)
