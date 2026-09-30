#!/usr/bin/env python3
"""Add missing imports for AI LLM helpers."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = ROOT / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"
t = p.read_text()

adds = []
if "import org.json.JSONArray" not in t:
    adds.append("import org.json.JSONArray")
if "import okhttp3.MediaType.Companion.toMediaType" not in t:
    adds.append("import okhttp3.MediaType.Companion.toMediaType")
if "import okhttp3.RequestBody.Companion.toRequestBody" not in t:
    adds.append("import okhttp3.RequestBody.Companion.toRequestBody")

if not adds:
    print("imports already present")
else:
    anchor = "import okhttp3.Request\n"
    if anchor not in t:
        anchor = "import okhttp3.OkHttpClient\n"
    if anchor not in t:
        anchor = "import org.json.JSONObject\n"
    t = t.replace(anchor, anchor + "".join(a + "\n" for a in adds), 1)
    p.write_text(t)
    print("added", adds)

print("InvokeEngine", p.stat().st_size)
