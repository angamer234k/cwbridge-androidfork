#!/usr/bin/env python3
"""
AI invoke service with web-configurable URL / token / model / API style.
  chat = OpenAI-compatible /v1/chat/completions
  responses = OpenAI /v1/responses
invoke|ai.<prompt> → paste model text (costs 2 against local.rbx)
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_server():
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()

    if "/api/ai-config" in t:
        print("Server: ai-config already present")
    else:
        anchor = 'path == "/api/admin-domain" && method == "POST" -> respond(out, 200, setAdminDomainJson(body))'
        route = '''
            path == "/api/ai-config" && method == "GET" -> respond(out, 200, aiConfigJson())
            path == "/api/ai-config" && method == "POST" -> respond(out, 200, setAiConfigJson(body))
'''
        if anchor in t:
            t = t.replace(anchor, anchor + "\n" + route, 1)
            print("Server: ai-config routes")
        else:
            anchor2 = 'path == "/api/admin-domain" && method == "GET" -> respond(out, 200, adminDomainJson())'
            if anchor2 in t:
                t = t.replace(anchor2, anchor2 + "\n" + route, 1)
                print("Server: ai-config routes (after admin GET)")
            else:
                raise SystemExit("admin-domain route anchor miss")

        handlers = r'''
    private fun aiConfigJson(): String {
        val url = com.cwbridge.android.data.UserFileStore.getSetting(context, "ai_url", "") ?: ""
        val model = com.cwbridge.android.data.UserFileStore.getSetting(context, "ai_model", "gpt-4o-mini") ?: "gpt-4o-mini"
        val style = com.cwbridge.android.data.UserFileStore.getSetting(context, "ai_style", "chat") ?: "chat"
        val tok = com.cwbridge.android.data.UserFileStore.getSetting(context, "ai_token", "") ?: ""
        val masked = when {
            tok.isEmpty() -> ""
            tok.length <= 8 -> "••••"
            else -> tok.take(4) + "…" + tok.takeLast(4)
        }
        return json(
            mapOf(
                "url" to url,
                "model" to model,
                "style" to style,
                "tokenSet" to tok.isNotEmpty(),
                "tokenMasked" to masked,
            ),
        )
    }

    private fun setAiConfigJson(body: String): String {
        val url = jsonString(body, "url").trim()
        val model = jsonString(body, "model").trim()
        val styleRaw = jsonString(body, "style").trim().lowercase()
        val token = jsonString(body, "token")
        val clearToken = bodyField(body, "clearToken")?.asBoolean == true

        if (url.isNotBlank()) {
            com.cwbridge.android.data.UserFileStore.putSetting(context, "ai_url", url.trimEnd('/'))
        }
        if (model.isNotBlank()) {
            com.cwbridge.android.data.UserFileStore.putSetting(context, "ai_model", model)
        }
        if (styleRaw.isNotBlank()) {
            val style = when (styleRaw) {
                "responses", "v2", "response" -> "responses"
                else -> "chat"
            }
            com.cwbridge.android.data.UserFileStore.putSetting(context, "ai_style", style)
        }
        if (clearToken) {
            com.cwbridge.android.data.UserFileStore.putSetting(context, "ai_token", "")
        } else if (token.isNotBlank()) {
            com.cwbridge.android.data.UserFileStore.putSetting(context, "ai_token", token.trim())
        }
        LogBuffer.i("Server", "ai-config updated style=${com.cwbridge.android.data.UserFileStore.getSetting(context, "ai_style", "chat")}")
        return aiConfigJson()
    }

'''
        if "fun aiConfigJson" not in t:
            mark = "    private fun adminDomainJson"
            if mark not in t:
                mark = "    private fun rateLimitsJson"
            if mark not in t:
                raise SystemExit("handler insert mark miss")
            t = t.replace(mark, handlers + "\n" + mark, 1)
            print("Server: ai-config handlers")

    p.write_text(t)
    print("LocalHttpServer", p.stat().st_size)


def patch_webui():
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = p.read_text()

    if 'id="sec-ai"' in t:
        print("WebUi: AI card already present")
    else:
        needle = '  <div class="card" id="sec-limits">'
        card = '''  <div class="card" id="sec-ai">
    <h2><span class="ms sm">smart_toy</span> AI (LLM)</h2>
    <p class="hint">Used by invoke|ai.prompt — OpenAI-compatible. Token stays on device. Style: chat = /v1/chat/completions · responses = /v1/responses.</p>
    <div class="row">
      <div class="field" style="flex:2"><label class="hint">Base URL</label>
        <input id="aiUrl" placeholder="https://api.openai.com"></div>
      <div class="field"><label class="hint">Model</label>
        <input id="aiModel" placeholder="gpt-4o-mini"></div>
    </div>
    <div class="row" style="margin-top:8px">
      <div class="field"><label class="hint">API style</label>
        <select id="aiStyle">
          <option value="chat">chat (v1 completions)</option>
          <option value="responses">responses (v2)</option>
        </select></div>
      <div class="field" style="flex:2"><label class="hint">API token</label>
        <input id="aiToken" type="password" placeholder="sk-… (leave blank to keep)" autocomplete="off"></div>
    </div>
    <div class="row" style="margin-top:8px">
      <button type="button" onclick="saveAiConfig()"><span class="ms sm">save</span> Save AI</button>
      <button type="button" class="ghost" onclick="loadAiConfig()"><span class="ms sm">refresh</span> Reload</button>
      <button type="button" class="ghost" onclick="clearAiToken()">Clear token</button>
    </div>
    <div id="aiMeta" class="hint" style="margin-top:8px"></div>
    <div id="aiMsg" class="msg"></div>
  </div>

'''
        if needle in t:
            t = t.replace(needle, card + needle, 1)
            print("WebUi: AI card")
        else:
            raise SystemExit("sec-limits not found")

    if 'href="#sec-ai"' not in t:
        nav = '<a href="#sec-limits">'
        if nav in t:
            t = t.replace(
                nav,
                '<a href="#sec-ai"><span class="ms sm">smart_toy</span> AI</a>\n  ' + nav,
                1,
            )
            print("WebUi: AI nav")

    if "async function loadAiConfig" not in t:
        js = r'''
async function loadAiConfig(){
  try{
    const j=await api('/api/ai-config');
    const u=document.getElementById('aiUrl');
    const m=document.getElementById('aiModel');
    const s=document.getElementById('aiStyle');
    const meta=document.getElementById('aiMeta');
    if(u) u.value=j.url||'';
    if(m) m.value=j.model||'';
    if(s) s.value=(j.style==='responses'?'responses':'chat');
    if(meta) meta.textContent=j.tokenSet?('Token: '+(j.tokenMasked||'set')):'Token: not set';
  }catch(e){}
}
async function saveAiConfig(){
  const body={
    url:(document.getElementById('aiUrl')&&document.getElementById('aiUrl').value||'').trim(),
    model:(document.getElementById('aiModel')&&document.getElementById('aiModel').value||'').trim(),
    style:(document.getElementById('aiStyle')&&document.getElementById('aiStyle').value)||'chat'
  };
  const tok=document.getElementById('aiToken');
  if(tok&&tok.value) body.token=tok.value;
  try{
    const j=await post('/api/ai-config', body);
    if(j.error){ msg('aiMsg', j.error, 'err'); toast(j.error,false); return; }
    if(tok) tok.value='';
    msg('aiMsg', 'AI settings saved', 'good');
    toast('AI settings saved', true);
    loadAiConfig();
  }catch(e){ msg('aiMsg', e.message||String(e), 'err'); }
}
async function clearAiToken(){
  try{
    await post('/api/ai-config', {clearToken:true});
    toast('Token cleared', true);
    loadAiConfig();
  }catch(e){ toast(e.message||String(e), false); }
}
'''
        if "async function loadLimits()" in t:
            t = t.replace("async function loadLimits()", js + "\nasync function loadLimits()", 1)
            print("WebUi: AI JS")
        elif "async function loadAdminDomain" in t:
            t = t.replace("async function loadAdminDomain", js + "\nasync function loadAdminDomain", 1)
            print("WebUi: AI JS via admin")
        else:
            print("WebUi: WARN no JS anchor")

    if "loadAiConfig();" not in t:
        if "loadAdminDomain();" in t:
            t = t.replace("loadAdminDomain();", "loadAdminDomain(); loadAiConfig();", 1)
            print("WebUi: boot loadAiConfig")
        elif "loadLimits();" in t:
            t = t.replace("loadLimits();", "loadLimits(); loadAiConfig();", 1)
            print("WebUi: boot loadAiConfig via limits")

    p.write_text(t)
    print("WebUi", p.stat().st_size)


AI_HANDLER = r'''            "ai" -> {
                // ai.<prompt> — uses web AI settings; pastes model text; costs 2
                val prompt = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                if (prompt.isEmpty()) {
                    replyErr("ai", "need ai.prompt")
                    return
                }
                val domain = Store.DEFAULT_DOMAIN
                val rl = DatastoreRateLimit.checkAndConsume(context, domain, cost = 2)
                if (rl != null) {
                    replyErr("ai", rl)
                    return
                }
                val url = UserFileStore.getSetting(context, "ai_url", "")?.trim().orEmpty()
                val token = UserFileStore.getSetting(context, "ai_token", "")?.trim().orEmpty()
                val model = UserFileStore.getSetting(context, "ai_model", "gpt-4o-mini")?.trim().orEmpty()
                    .ifBlank { "gpt-4o-mini" }
                val style = UserFileStore.getSetting(context, "ai_style", "chat")?.trim()?.lowercase().orEmpty()
                if (url.isEmpty()) {
                    replyErr("ai", "configure AI URL in web panel")
                    return
                }
                if (token.isEmpty()) {
                    replyErr("ai", "configure AI token in web panel")
                    return
                }
                try {
                    val text = withContext(Dispatchers.IO) {
                        callLlm(baseUrl = url, token = token, model = model, style = style, prompt = prompt)
                    }
                    if (text.isBlank()) {
                        replyErr("ai", "empty model reply")
                        return
                    }
                    pasteIntoGame(text)
                    replyOk("ai", text.take(500))
                } catch (t: Throwable) {
                    replyErr("ai", t.message ?: "llm failed")
                }
            }
'''

AI_HELPERS = r'''
    /**
     * OpenAI-compatible call.
     * style "chat" → POST {base}/v1/chat/completions
     * style "responses" → POST {base}/v1/responses
     */
    private fun callLlm(
        baseUrl: String,
        token: String,
        model: String,
        style: String,
        prompt: String,
    ): String {
        val root = baseUrl.trim().trimEnd('/')
        val useResponses = style == "responses" || style == "v2" || style == "response"
        val endpoint = when {
            useResponses && root.endsWith("/v1/responses") -> root
            useResponses && root.endsWith("/v1") -> "$root/responses"
            useResponses -> "$root/v1/responses"
            root.endsWith("/v1/chat/completions") -> root
            root.endsWith("/v1") -> "$root/chat/completions"
            else -> "$root/v1/chat/completions"
        }
        val jsonBody = if (useResponses) {
            JSONObject()
                .put("model", model)
                .put("input", prompt)
                .toString()
        } else {
            val messages = JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt),
            )
            JSONObject()
                .put("model", model)
                .put("messages", messages)
                .toString()
        }
        val media = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(media))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val brief = body.take(180).replace('\n', ' ')
                error("HTTP ${resp.code}: $brief")
            }
            val obj = JSONObject(body)
            return if (useResponses) {
                extractResponsesText(obj)
            } else {
                obj.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            }
        }
    }

    private fun extractResponsesText(obj: JSONObject): String {
        if (obj.has("output_text")) {
            val ot = obj.optString("output_text", "")
            if (ot.isNotBlank()) return ot.trim()
        }
        val output = obj.optJSONArray("output") ?: return obj.toString().take(500)
        val sb = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val text = part.optString("text", "")
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }
            }
        }
        val out = sb.toString().trim()
        if (out.isNotEmpty()) return out
        error("no text in responses payload")
    }

'''


def patch_invoke():
    p = ROOT / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"
    t = p.read_text()

    if "import org.json.JSONObject" not in t:
        t = t.replace(
            "import okhttp3.Request",
            "import okhttp3.Request\nimport okhttp3.MediaType.Companion.toMediaType\n"
            "import okhttp3.RequestBody.Companion.toRequestBody\nimport org.json.JSONArray\nimport org.json.JSONObject",
            1,
        )
        if "import org.json.JSONObject" not in t:
            t = t.replace(
                "import kotlinx.coroutines.launch",
                "import kotlinx.coroutines.launch\nimport org.json.JSONArray\nimport org.json.JSONObject\n"
                "import okhttp3.MediaType.Companion.toMediaType\nimport okhttp3.RequestBody.Companion.toRequestBody",
                1,
            )
        print("Invoke: JSON/body imports")

    if "import com.cwbridge.android.data.UserFileStore" not in t:
        t = t.replace(
            "import com.cwbridge.android.data.Store",
            "import com.cwbridge.android.data.Store\nimport com.cwbridge.android.data.UserFileStore",
            1,
        )

    start = t.find('            "ai" -> {')
    if start < 0:
        else_m = '            else -> replyErr(request, "unknown request — invoke|help")'
        if else_m not in t:
            raise SystemExit("ai and else marker missing")
        t = t.replace(else_m, AI_HANDLER + "\n" + else_m, 1)
        print("Invoke: inserted ai handler")
    else:
        end = t.find('            else ->', start)
        if end < 0:
            end = t.find('\n            "', start + 20)
        if end < 0:
            raise SystemExit("cannot find end of ai block")
        t = t[:start] + AI_HANDLER + "\n" + t[end:]
        print("Invoke: replaced ai handler")

    if "fun callLlm" not in t:
        helpers = AI_HELPERS
        anchor = "    private fun replyOk"
        if anchor not in t:
            anchor = "    private fun httpGet"
        if anchor not in t:
            raise SystemExit("helper anchor miss")
        t = t.replace(anchor, helpers + "\n" + anchor, 1)
        print("Invoke: LLM helpers")

    p.write_text(t)
    print("InvokeEngine", p.stat().st_size)


def main():
    patch_server()
    patch_webui()
    patch_invoke()
    print("hotfix ai OK")


if __name__ == "__main__":
    main()
