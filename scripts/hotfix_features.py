#!/usr/bin/env python3
"""Fix broken stream JS that fails check-webui (Unexpected end of input)."""
from pathlib import Path
import re

p = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
t = p.read_text()

# Replace any streamTimer block through loadShot/bindShot with a clean loadShot
simple = r'''async function loadShot(){
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
}

'''

# Find from first stream/loadShot mess to loadAdminDomain
idx = t.find("var streamTimer")
if idx < 0:
    idx = t.find("async function loadShot()")
if idx < 0:
    raise SystemExit("loadShot not found")

end_markers = [
    t.find("\nasync function loadAdminDomain", idx),
    t.find("\nasync function loadAiConfig", idx),
    t.find("\nasync function loadLimits", idx),
]
ends = [e for e in end_markers if e > idx]
if not ends:
    raise SystemExit("end marker not found")
end = min(ends)
t = t[:idx] + simple + t[end:]

# Soften stream button if present (no-op warning)
t = t.replace(
    'onclick="toggleStream()"',
    'onclick="msg(\'ctlMsg\',\'stream coming next build\',\'\')"',
)

p.write_text(t)
print("WebUi fixed", p.stat().st_size)
