#!/usr/bin/env python3
"""Macro-style service builder in web UI + proper service JSON GET."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_server():
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()
    old = """        if (method == "GET" && sub.isEmpty()) {
            return json(mapOf("service" to existing))
        }"""
    new = """        if (method == "GET" && sub.isEmpty()) {
            // serviceGson emits _kind on triggers/actions for round-trip edit
            return \"\"\"{\"service\":${serviceGson.toJson(existing)}}\"\"\"
        }"""
    if old in t:
        t = t.replace(old, new, 1)
        print("Server: GET service uses serviceGson")
    else:
        print("Server: GET service skip")
    p.write_text(t)
    print("LocalHttpServer", p.stat().st_size)


def patch_webui():
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = p.read_text()

    old_card = """  <div class=\"card\" id=\"sec-services\">
    <h2><span class=\"ms sm\">extension</span> Services</h2>
    <div id=\"svcList\"></div>
    <div class=\"row\" style=\"margin-top:10px\">
      <div class=\"field\"><input id=\"svcName\" placeholder=\"New service name\"></div>
      <button type=\"button\" onclick=\"createService()\"><span class=\"ms sm\">add</span> Create</button>
    </div>
    <div id=\"svcMsg\" class=\"msg\"></div>
  </div>"""

    new_card = """  <div class=\"card\" id=\"sec-services\">
    <h2><span class=\"ms sm\">extension</span> Services</h2>
    <p class=\"hint\">Macros with triggers + actions. Enabled = always listening (log/time). Edit anytime.</p>
    <div id=\"svcList\"></div>
    <div class=\"row\" style=\"margin-top:10px\">
      <div class=\"field\"><input id=\"svcName\" placeholder=\"New macro name\"></div>
      <button type=\"button\" onclick=\"createService()\"><span class=\"ms sm\">add</span> Create</button>
    </div>
    <div id=\"svcEditor\" style=\"display:none;margin-top:12px;padding-top:12px;border-top:1px solid var(--line)\">
      <div class=\"row\">
        <div class=\"field\"><label class=\"hint\">Name</label><input id=\"edName\"></div>
        <div class=\"field\"><label class=\"hint\">Description</label><input id=\"edDesc\" placeholder=\"optional\"></div>
        <label class=\"hint\" style=\"display:flex;align-items:center;gap:6px;padding-top:18px\">
          <input type=\"checkbox\" id=\"edEnabled\" checked> Enabled (always on)
        </label>
      </div>
      <h3 style=\"font-size:12px;letter-spacing:.06em;color:var(--muted);margin:14px 0 6px\">TRIGGERS</h3>
      <div id=\"edTriggers\"></div>
      <div class=\"row\" style=\"margin-top:6px\">
        <button type=\"button\" class=\"ghost\" onclick=\"addTrigger('LOG')\"><span class=\"ms sm\">article</span> Log match</button>
        <button type=\"button\" class=\"ghost\" onclick=\"addTrigger('TIME')\"><span class=\"ms sm\">schedule</span> Interval</button>
      </div>
      <h3 style=\"font-size:12px;letter-spacing:.06em;color:var(--muted);margin:14px 0 6px\">ACTIONS</h3>
      <div id=\"edActions\"></div>
      <div class=\"row\" style=\"margin-top:6px\">
        <select id=\"edAddAct\" style=\"max-width:160px\">
          <option value=\"DELAY\">Delay</option>
          <option value=\"CTRL_T\">Ctrl+T</option>
          <option value=\"PRESS_ENTER\">Enter</option>
          <option value=\"SEND_TEXT\">Send text</option>
          <option value=\"ENTER_TEXT\">Paste + Enter</option>
          <option value=\"SET_VAR\">Set variable</option>
          <option value=\"TEXT_MAN\">TextMan</option>
          <option value=\"TAP\">Tap</option>
        </select>
        <button type=\"button\" class=\"ghost\" onclick=\"addAction()\"><span class=\"ms sm\">add</span> Add action</button>
      </div>
      <div class=\"row\" style=\"margin-top:12px\">
        <button type=\"button\" onclick=\"saveEditor()\"><span class=\"ms sm\">save</span> Save macro</button>
        <button type=\"button\" class=\"ghost\" onclick=\"closeEditor()\">Close</button>
        <button type=\"button\" class=\"soft\" onclick=\"runEditor()\"><span class=\"ms sm\">play_arrow</span> Run now</button>
      </div>
      <input type=\"hidden\" id=\"edId\">
    </div>
    <div id=\"svcMsg\" class=\"msg\"></div>
  </div>"""

    if old_card in t:
        t = t.replace(old_card, new_card, 1)
        print("WebUi: services card → macro builder")
    elif 'id=\"svcEditor\"' in t:
        print("WebUi: editor already present")
    else:
        raise SystemExit("services card not found")

    start = t.find("async function refreshServices()")
    if start < 0:
        raise SystemExit("refreshServices not found")
    end = t.find("async function refreshStore()", start)
    if end < 0:
        end = t.find("async function refreshVars()", start)
    if end < 0:
        raise SystemExit("end of services JS not found")

    new_js = r"""
var ED = {id:'',name:'',description:'',isEnabled:true,triggers:[],actions:[]};
function uid(){ return String(Date.now()) + Math.floor(Math.random()*1000); }
async function refreshServices(){
  try{
    var s = await api("/api/services");
    var list = s.services || [];
    if(!list.length){
      D("svcList").innerHTML = "<div class=\"empty\"><span class=\"ms\">extension_off</span>No macros yet — create one</div>";
      return;
    }
    D("svcList").innerHTML = list.map(function(sv){
      var on = sv.enabled !== false;
      return "<div class=\"row\" style=\"margin-bottom:8px;align-items:center\">" +
        "<span style=\"flex:1;min-width:0\"><b>" + esc(sv.name||sv.id) + "</b>" +
        " <span class=\"hint\">" + (sv.triggers||0) + " trig · " + (sv.actions||0) + " act" +
        (on ? " · <span class=\"ok\">ON</span>" : " · <span class=\"bad\">OFF</span>") + "</span></span>" +
        "<button type=\"button\" class=\"ghost\" onclick=\"toggleService(" + arg(sv.id) + "," + (!on) + ")\">" +
        (on ? "Disable" : "Enable") + "</button>" +
        "<button type=\"button\" class=\"soft\" onclick=\"editService(" + arg(sv.id) + ")\">Edit</button>" +
        "<button type=\"button\" class=\"soft\" onclick=\"runService(" + arg(sv.id) + ")\">Run</button>" +
        "<button type=\"button\" class=\"ghost\" onclick=\"deleteService(" + arg(sv.id) + ")\">Del</button></div>";
    }).join("");
  }catch(e){}
}
async function createService(){
  try{
    var name = (D("svcName").value||"").trim();
    if(!name){ msg("svcMsg","name required","err"); return; }
    var r = await post("/api/services", {name: name});
    msg("svcMsg", r.message || "created", "good");
    D("svcName").value = "";
    await refreshServices();
    if(r.id) editService(r.id);
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function runService(id){
  try{
    var r = await post("/api/services/" + encodeURIComponent(id) + "/run");
    msg("svcMsg", r.message || "ran", "good"); toast(r.message||"ran", true);
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function toggleService(id, enabled){
  try{
    var r = await post("/api/services/" + encodeURIComponent(id), {enabled: !!enabled});
    msg("svcMsg", r.message || "ok", "good");
    refreshServices();
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function deleteService(id){
  if(!confirm("Delete this macro?")) return;
  try{
    var r = await api("/api/services/" + encodeURIComponent(id), {method:"DELETE"});
    msg("svcMsg", (r && r.message) || "deleted", "good");
    if(ED.id===id) closeEditor();
    refreshServices();
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function editService(id){
  try{
    var r = await api("/api/services/" + encodeURIComponent(id));
    var s = r.service;
    if(!s){ msg("svcMsg","load failed","err"); return; }
    ED = {
      id: s.id,
      name: s.name||"",
      description: s.description||"",
      isEnabled: s.isEnabled !== false && s.enabled !== false,
      triggers: (s.triggers||[]).slice(),
      actions: (s.actions||[]).slice()
    };
    D("edId").value = ED.id;
    D("edName").value = ED.name;
    D("edDesc").value = ED.description;
    D("edEnabled").checked = !!ED.isEnabled;
    renderEditorLists();
    D("svcEditor").style.display = "block";
    D("svcEditor").scrollIntoView({behavior:"smooth",block:"nearest"});
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
function closeEditor(){
  D("svcEditor").style.display = "none";
  ED = {id:'',name:'',description:'',isEnabled:true,triggers:[],actions:[]};
}
function renderEditorLists(){
  var tg = D("edTriggers");
  if(!ED.triggers.length) tg.innerHTML = "<div class=\"hint\">No triggers — macro only runs via Run button</div>";
  else tg.innerHTML = ED.triggers.map(function(tr,i){
    var kind = (tr._kind||tr.type||"LOG").toString().toUpperCase();
    var body = "";
    if(kind==="TIME"){
      body = "every <input type=\"number\" style=\"width:90px\" value=\"" + esc(String(tr.intervalMs||1000)) +
        "\" onchange=\"ED.triggers["+i+"].intervalMs=parseInt(this.value,10)||1000\"> ms";
    } else {
      body = "pattern <input style=\"min-width:140px\" value=\"" + esc(tr.pattern||"") +
        "\" onchange=\"ED.triggers["+i+"].pattern=this.value\">";
    }
    return "<div class=\"row\" style=\"margin-bottom:4px\"><span class=\"chip\">" + esc(kind) +
      "</span> " + body +
      " <button type=\"button\" class=\"ghost\" onclick=\"ED.triggers.splice("+i+",1);renderEditorLists()\">×</button></div>";
  }).join("");
  var ac = D("edActions");
  if(!ED.actions.length) ac.innerHTML = "<div class=\"hint\">No actions yet</div>";
  else ac.innerHTML = ED.actions.map(function(a,i){
    var kind = (a._kind||a.type||"?").toString().toUpperCase();
    var body = "";
    if(kind==="DELAY") body = "<input type=\"number\" style=\"width:90px\" value=\""+esc(String(a.milliseconds||1000))+"\" onchange=\"ED.actions["+i+"].milliseconds=parseInt(this.value,10)||1000\"> ms";
    else if(kind==="SEND_TEXT"||kind==="ENTER_TEXT") body = "<input style=\"min-width:160px\" value=\""+esc(a.text||"")+"\" onchange=\"ED.actions["+i+"].text=this.value\" placeholder=\"text / $var\">";
    else if(kind==="SET_VAR") body = "key <input style=\"width:90px\" value=\""+esc(a.key||"")+"\" onchange=\"ED.actions["+i+"].key=this.value\"> = <input style=\"width:120px\" value=\""+esc(a.value||"")+"\" onchange=\"ED.actions["+i+"].value=this.value\">";
    else if(kind==="TEXT_MAN") body = "src <input style=\"width:90px\" value=\""+esc(a.source||"$lastMatch")+"\" onchange=\"ED.actions["+i+"].source=this.value\"> → <input style=\"width:90px\" value=\""+esc(a.saveTo||"result")+"\" onchange=\"ED.actions["+i+"].saveTo=this.value\">";
    else if(kind==="TAP") body = "text <input style=\"width:120px\" value=\""+esc(a.text||"")+"\" onchange=\"ED.actions["+i+"].text=this.value\" placeholder=\"or use %\">";
    else body = "<span class=\"hint\">(no params)</span>";
    return "<div class=\"row\" style=\"margin-bottom:4px\"><span class=\"chip\">" + (i+1) + ". " + esc(kind) +
      "</span> " + body +
      " <button type=\"button\" class=\"ghost\" onclick=\"ED.actions.splice("+i+",1);renderEditorLists()\">×</button></div>";
  }).join("");
}
function addTrigger(kind){
  if(kind==="TIME"){
    ED.triggers.push({_kind:"TIME",id:uid(),name:"Interval",intervalMs:5000,repeat:true,type:"TIME"});
  } else {
    ED.triggers.push({_kind:"LOG",id:uid(),name:"Log",pattern:"invoke|",matchCase:false,useRegex:false,type:"LOG"});
  }
  renderEditorLists();
}
function addAction(){
  var kind = (D("edAddAct").value||"DELAY").toUpperCase();
  var a = {_kind:kind,id:uid(),name:kind,type:kind};
  if(kind==="DELAY") a.milliseconds = 500;
  if(kind==="SEND_TEXT"||kind==="ENTER_TEXT") a.text = "";
  if(kind==="SET_VAR"){ a.key=""; a.value=""; }
  if(kind==="TEXT_MAN"){ a.source="$lastMatch"; a.mode="full"; a.pattern=""; a.group=1; a.replaceWith=""; a.saveTo="result"; }
  if(kind==="TAP"){ a.text=""; }
  ED.actions.push(a);
  renderEditorLists();
}
async function saveEditor(){
  if(!ED.id){ msg("svcMsg","nothing open","err"); return; }
  ED.name = (D("edName").value||"").trim() || ED.name;
  ED.description = D("edDesc").value||"";
  ED.isEnabled = !!D("edEnabled").checked;
  ED.triggers.forEach(function(tr){ if(!tr._kind) tr._kind = (tr.type||"LOG"); });
  ED.actions.forEach(function(a){ if(!a._kind) a._kind = (a.type||"DELAY"); });
  try{
    var r = await post("/api/services/" + encodeURIComponent(ED.id), {json: JSON.stringify(ED)});
    if(r.error){ msg("svcMsg", r.error, "err"); toast(r.error,false); return; }
    msg("svcMsg", r.message || "saved", "good"); toast("Macro saved", true);
    refreshServices();
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function runEditor(){
  if(!ED.id) return;
  await saveEditor();
  await runService(ED.id);
}

"""

    t = t[:start] + new_js + t[end:]
    print("WebUi: services JS replaced")

    p.write_text(t)
    print("WebUi", p.stat().st_size)


def main():
    patch_server()
    patch_webui()
    print("hotfix macro OK")


if __name__ == "__main__":
    main()
