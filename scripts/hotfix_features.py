#!/usr/bin/env python3
from pathlib import Path

p = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
t = p.read_text()

if "function toggleStream()" in t and "async function refreshShot" in t:
    print("stream JS already present")
else:
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
  msg('ctlMsg','stream on - tap image to control','good');
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
    if idx < 0:
        raise SystemExit("loadShot missing")
    rest = t[idx:]
    ends = [c for c in [rest.find("\nasync function ", 10), rest.find("\nfunction ", 10)] if c > 0]
    if not ends:
        raise SystemExit("loadShot end miss")
    end = min(ends)
    t = t[:idx] + stream_js + rest[end:]
    p.write_text(t)
    print("stream JS injected", p.stat().st_size)

if 'id="btnStream"' not in t and 'id=\"btnStream\"' not in t:
    # ensure button exists
    marker = "<!--SCREENSHOT_BUTTON-->"
    if marker in t and "btnStream" not in t:
        t = t.replace(
            marker,
            marker + '\n      <button type="button" id="btnStream" onclick="toggleStream()"><span class="ms sm">live_tv</span> Start stream</button>',
            1,
        )
        p.write_text(t)
        print("btnStream added")

print("done")
