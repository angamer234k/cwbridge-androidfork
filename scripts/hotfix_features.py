#!/usr/bin/env python3
"""AI: temperature + max_tokens settings."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENG = ROOT / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"
SRV = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"


def main():
    t = ENG.read_text()
    n = 0

    old_sig = (
        "        prompt: String,\n"
        "        timeoutSec: Int = 90,\n"
        "    ): String {"
    )
    new_sig = (
        "        prompt: String,\n"
        "        timeoutSec: Int = 90,\n"
        "        temperature: Double = 0.7,\n"
        "        maxTokens: Int = 1024,\n"
        "    ): String {"
    )
    if "maxTokens: Int" not in t:
        if old_sig not in t:
            raise SystemExit("sig miss")
        t = t.replace(old_sig, new_sig, 1)
        n += 1
        print("sig")
    else:
        print("sig already")
        n += 1

    old_body = (
        "        val jsonBody = if (useResponses) {\n"
        "            JSONObject()\n"
        "                .put(\"model\", model)\n"
        "                .put(\"input\", prompt)\n"
        "                .toString()\n"
        "        } else {\n"
        "            val messages = JSONArray().put(\n"
        "                JSONObject().put(\"role\", \"user\").put(\"content\", prompt),\n"
        "            )\n"
        "            JSONObject()\n"
        "                .put(\"model\", model)\n"
        "                .put(\"messages\", messages)\n"
        "                .toString()\n"
        "        }"
    )
    new_body = (
        "        val temp = temperature.coerceIn(0.0, 2.0)\n"
        "        val maxTok = maxTokens.coerceIn(1, 16384)\n"
        "        val jsonBody = if (useResponses) {\n"
        "            JSONObject()\n"
        "                .put(\"model\", model)\n"
        "                .put(\"input\", prompt)\n"
        "                .put(\"temperature\", temp)\n"
        "                .put(\"max_output_tokens\", maxTok)\n"
        "                .toString()\n"
        "        } else {\n"
        "            val messages = JSONArray().put(\n"
        "                JSONObject().put(\"role\", \"user\").put(\"content\", prompt),\n"
        "            )\n"
        "            JSONObject()\n"
        "                .put(\"model\", model)\n"
        "                .put(\"messages\", messages)\n"
        "                .put(\"temperature\", temp)\n"
        "                .put(\"max_tokens\", maxTok)\n"
        "                .toString()\n"
        "        }"
    )
    if "max_output_tokens" not in t:
        if old_body not in t:
            raise SystemExit("body miss")
        t = t.replace(old_body, new_body, 1)
        n += 1
        print("body")
    else:
        print("body already")
        n += 1

    old_call = (
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
    new_call = (
        "                    val timeoutSec = UserFileStore.getSetting(context, \"ai_timeout_sec\", \"90\")\n"
        "                        ?.trim()?.toIntOrNull()?.coerceIn(15, 300) ?: 90\n"
        "                    val temperature = UserFileStore.getSetting(context, \"ai_temperature\", \"0.7\")\n"
        "                        ?.trim()?.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 0.7\n"
        "                    val maxTokens = UserFileStore.getSetting(context, \"ai_max_tokens\", \"1024\")\n"
        "                        ?.trim()?.toIntOrNull()?.coerceIn(1, 16384) ?: 1024\n"
        "                    val text = withContext(Dispatchers.IO) {\n"
        "                        callLlm(\n"
        "                            baseUrl = url,\n"
        "                            token = token,\n"
        "                            model = model,\n"
        "                            style = style,\n"
        "                            prompt = prompt,\n"
        "                            timeoutSec = timeoutSec,\n"
        "                            temperature = temperature,\n"
        "                            maxTokens = maxTokens,\n"
        "                        )\n"
        "                    }"
    )
    if "ai_temperature" not in t:
        if old_call not in t:
            raise SystemExit("call miss")
        t = t.replace(old_call, new_call, 1)
        n += 1
        print("call")
    else:
        print("call already")
        n += 1

    ENG.write_text(t)
    print("engine", n, ENG.stat().st_size)

    s = SRV.read_text()
    sn = 0
    if "ai_temperature" not in s:
        old_get = (
            '        val timeoutSec = com.cwbridge.android.data.UserFileStore.getSetting(context, "ai_timeout_sec", "90") ?: "90"\n'
            '        val masked = when {\n'
        )
        new_get = (
            '        val timeoutSec = com.cwbridge.android.data.UserFileStore.getSetting(context, "ai_timeout_sec", "90") ?: "90"\n'
            '        val temperature = com.cwbridge.android.data.UserFileStore.getSetting(context, "ai_temperature", "0.7") ?: "0.7"\n'
            '        val maxTokens = com.cwbridge.android.data.UserFileStore.getSetting(context, "ai_max_tokens", "1024") ?: "1024"\n'
            '        val masked = when {\n'
        )
        if old_get in s:
            s = s.replace(old_get, new_get, 1)
            sn += 1
        old_map = (
            '                "timeoutSec" to (timeoutSec.toIntOrNull()?.coerceIn(15, 300) ?: 90),\n'
        )
        new_map = (
            '                "timeoutSec" to (timeoutSec.toIntOrNull()?.coerceIn(15, 300) ?: 90),\n'
            '                "temperature" to (temperature.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 0.7),\n'
            '                "maxTokens" to (maxTokens.toIntOrNull()?.coerceIn(1, 16384) ?: 1024),\n'
        )
        if old_map in s:
            s = s.replace(old_map, new_map, 1)
            sn += 1
        old_set = (
            '        val timeoutRaw = jsonString(body, "timeoutSec").trim()\n'
        )
        new_set = (
            '        val timeoutRaw = jsonString(body, "timeoutSec").trim()\n'
            '        val temperatureRaw = jsonString(body, "temperature").trim()\n'
            '        val maxTokensRaw = jsonString(body, "maxTokens").trim()\n'
        )
        if old_set in s:
            s = s.replace(old_set, new_set, 1)
            sn += 1
        timeout_block = (
            '        if (timeoutRaw.isNotBlank()) {\n'
            '            val sec = timeoutRaw.toIntOrNull()?.coerceIn(15, 300) ?: 90\n'
            '            com.cwbridge.android.data.UserFileStore.putSetting(context, "ai_timeout_sec", sec.toString())\n'
            '        }\n'
        )
        if timeout_block in s and "ai_temperature" not in s:
            extra = (
                '        if (timeoutRaw.isNotBlank()) {\n'
                '            val sec = timeoutRaw.toIntOrNull()?.coerceIn(15, 300) ?: 90\n'
                '            com.cwbridge.android.data.UserFileStore.putSetting(context, "ai_timeout_sec", sec.toString())\n'
                '        }\n'
                '        if (temperatureRaw.isNotBlank()) {\n'
                '            val temp = temperatureRaw.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 0.7\n'
                '            com.cwbridge.android.data.UserFileStore.putSetting(context, "ai_temperature", temp.toString())\n'
                '        }\n'
                '        if (maxTokensRaw.isNotBlank()) {\n'
                '            val mt = maxTokensRaw.toIntOrNull()?.coerceIn(1, 16384) ?: 1024\n'
                '            com.cwbridge.android.data.UserFileStore.putSetting(context, "ai_max_tokens", mt.toString())\n'
                '        }\n'
            )
            s = s.replace(timeout_block, extra, 1)
            sn += 1
        SRV.write_text(s)
        print("server", sn, SRV.stat().st_size)
    else:
        print("server already")

    if n < 3:
        raise SystemExit("incomplete %s" % n)


if __name__ == "__main__":
    main()
