#!/usr/bin/env python3
"""AI timeouts for OpenRouter free tier (default 90s, setting ai_timeout_sec)."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENG = ROOT / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"
SRV = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"


def main():
    n = 0
    t = ENG.read_text()

    old_client = "    private val httpClient = OkHttpClient()\n"
    new_client = (
        "    private val httpClient = OkHttpClient.Builder()\n"
        "        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)\n"
        "        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)\n"
        "        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)\n"
        "        .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)\n"
        "        .build()\n"
    )
    if old_client in t:
        t = t.replace(old_client, new_client, 1)
        n += 1
        print("client")
    elif "callTimeout" in t and "OkHttpClient.Builder()" in t:
        print("client already")
        n += 1
    else:
        raise SystemExit("client miss")

    old_sig = (
        "    private fun callLlm(\n"
        "        baseUrl: String,\n"
        "        token: String,\n"
        "        model: String,\n"
        "        style: String,\n"
        "        prompt: String,\n"
        "    ): String {"
    )
    new_sig = (
        "    private fun callLlm(\n"
        "        baseUrl: String,\n"
        "        token: String,\n"
        "        model: String,\n"
        "        style: String,\n"
        "        prompt: String,\n"
        "        timeoutSec: Int = 90,\n"
        "    ): String {"
    )
    if old_sig in t:
        t = t.replace(old_sig, new_sig, 1)
        n += 1
        print("sig")
    elif "timeoutSec: Int = 90" in t:
        print("sig already")
        n += 1
    else:
        raise SystemExit("sig miss")

    needle = (
        '            .header("Authorization", "Bearer $token")\n'
        '            .header("Content-Type", "application/json")\n'
        '            .post(jsonBody.toRequestBody(media))\n'
        '            .build()\n'
        '        httpClient.newCall(req).execute().use { resp ->\n'
    )
    new_exec = (
        '            .header("Authorization", "Bearer $token")\n'
        '            .header("Content-Type", "application/json")\n'
        '            .post(jsonBody.toRequestBody(media))\n'
        '            .build()\n'
        '        val sec = timeoutSec.coerceIn(15, 300)\n'
        '        val client = httpClient.newBuilder()\n'
        '            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)\n'
        '            .readTimeout(sec.toLong(), java.util.concurrent.TimeUnit.SECONDS)\n'
        '            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)\n'
        '            .callTimeout((sec + 15).toLong(), java.util.concurrent.TimeUnit.SECONDS)\n'
        '            .build()\n'
        '        try {\n'
        '            client.newCall(req).execute().use { resp ->\n'
    )
    if needle in t:
        t = t.replace(needle, new_exec, 1)
        n += 1
        print("exec")
    elif "AI timeout after" in t:
        print("exec already")
        n += 1
    else:
        raise SystemExit("exec miss")

    old_tail = (
        '                obj.getJSONArray("choices")\n'
        '                    .getJSONObject(0)\n'
        '                    .getJSONObject("message")\n'
        '                    .getString("content")\n'
        '                    .trim()\n'
        '            }\n'
        '        }\n'
        '    }\n'
        '\n'
        '    private fun extractResponsesText'
    )
    new_tail = (
        '                obj.getJSONArray("choices")\n'
        '                    .getJSONObject(0)\n'
        '                    .getJSONObject("message")\n'
        '                    .getString("content")\n'
        '                    .trim()\n'
        '            }\n'
        '            }\n'
        '        } catch (e: java.net.SocketTimeoutException) {\n'
        '            error("AI timeout after ${sec}s — raise ai_timeout_sec (15-300) in settings")\n'
        '        } catch (e: java.io.InterruptedIOException) {\n'
        '            error("AI timeout after ${sec}s — raise ai_timeout_sec (15-300) in settings")\n'
        '        }\n'
        '    }\n'
        '\n'
        '    private fun extractResponsesText'
    )
    if old_tail in t:
        t = t.replace(old_tail, new_tail, 1)
        n += 1
        print("tail")
    elif "SocketTimeoutException" in t:
        print("tail already")
        n += 1
    else:
        raise SystemExit("tail miss")

    old_call = (
        "                    val text = withContext(Dispatchers.IO) {\n"
        "                        callLlm(baseUrl = url, token = token, model = model, style = style, prompt = prompt)\n"
        "                    }"
    )
    new_call = (
        "                    val timeoutSec = UserFileStore.getSetting(context, \"ai_timeout_sec\", \"90\")\n"
        "                        ?.trim()?.toIntOrNull()?.coerceIn(15, 300) ?: 90\n"
        "                    val text = withContext(Dispatchers.IO) {\n"
        "                        callLlm(\n"
        "                            baseUrl = url,\n"
        "                            token = token,\n"
        "                            model = model,\n"
        "                            style = style,\n"
        "                            prompt = prompt,\n"
        "                            timeoutSec = timeoutSec,\n"
        "                        )\n"
        "                    }"
    )
    if old_call in t:
        t = t.replace(old_call, new_call, 1)
        n += 1
        print("call")
    elif "ai_timeout_sec" in t:
        print("call already")
        n += 1
    else:
        raise SystemExit("call miss")

    ENG.write_text(t)
    print("engine ok", n, ENG.stat().st_size)

    s = SRV.read_text()
    if "ai_timeout_sec" not in s:
        sn = 0
        g = (
            '        val tok = com.cwbridge.android.data.UserFileStore.getSetting(context, "ai_token", "") ?: ""\n'
            '        val masked = when {\n'
        )
        g2 = (
            '        val tok = com.cwbridge.android.data.UserFileStore.getSetting(context, "ai_token", "") ?: ""\n'
            '        val timeoutSec = com.cwbridge.android.data.UserFileStore.getSetting(context, "ai_timeout_sec", "90") ?: "90"\n'
            '        val masked = when {\n'
        )
        if g in s:
            s = s.replace(g, g2, 1)
            sn += 1
        needle2 = (
            '                "tokenSet" to tok.isNotEmpty(),\n'
            '                "tokenMasked" to masked,\n'
        )
        if needle2 in s:
            s = s.replace(
                needle2,
                needle2 + '                "timeoutSec" to (timeoutSec.toIntOrNull()?.coerceIn(15, 300) ?: 90),\n',
                1,
            )
            sn += 1
        old_set = (
            '        val token = jsonString(body, "token")\n'
            '        val clearToken = bodyField(body, "clearToken")?.asBoolean == true\n'
        )
        if old_set in s:
            s = s.replace(
                old_set,
                old_set + '        val timeoutRaw = jsonString(body, "timeoutSec").trim()\n',
                1,
            )
            sn += 1
        insert_after = 'com.cwbridge.android.data.UserFileStore.putSetting(context, "ai_style", style)\n        }\n'
        idx = s.find("fun setAiConfigJson")
        if idx > 0 and "ai_timeout_sec" not in s[idx : idx + 1500]:
            pos = s.find(insert_after, idx)
            if pos > 0:
                add = (
                    'com.cwbridge.android.data.UserFileStore.putSetting(context, "ai_style", style)\n'
                    '        }\n'
                    '        if (timeoutRaw.isNotBlank()) {\n'
                    '            val sec = timeoutRaw.toIntOrNull()?.coerceIn(15, 300) ?: 90\n'
                    '            com.cwbridge.android.data.UserFileStore.putSetting(context, "ai_timeout_sec", sec.toString())\n'
                    '        }\n'
                )
                s = s[:pos] + add + s[pos + len(insert_after) :]
                sn += 1
        SRV.write_text(s)
        print("server ok", sn, SRV.stat().st_size)
    else:
        print("server already")

    if n < 5:
        raise SystemExit("incomplete engine %s" % n)


if __name__ == "__main__":
    main()
