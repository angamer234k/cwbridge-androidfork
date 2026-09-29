#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"

def main() -> None:
    t = P.read_text()
    old = '''    private fun storeKeysJson(query: Map<String, String>): String {
        val domain = query["domain"].orEmpty()
        if (domain.isBlank()) {
            return json(mapOf("error" to "domain required (?domain=name.rbx)"))
        }
        val d = Store.normalizeDomain(domain)
            ?: return json(mapOf("error" to "bad domain '$domain'"))
        val prefix = "$d::"
        val all = com.cwbridge.android.data.UserFileStore.storeAll(context)
        val keys = all.entries
            .filter { it.key.startsWith(prefix) }
            .map { e ->
                val key = e.key.removePrefix(prefix)
                mapOf(
                    "key" to key,
                    "value" to e.value,
                    "bytes" to e.value.toByteArray(Charsets.UTF_8).size,
                )
            }
            .sortedBy { it["key"] as String }
        return json(
            mapOf(
                "domain" to d,
                "keys" to keys,
                "used" to Store.formatBytes(store.usageOf(d)),
                "limit" to Store.formatBytes(store.limitOf(d)),
                "limitBytes" to store.limitOf(d),
            ),
        )
    }'''
    new = '''    private fun storeKeysJson(query: Map<String, String>): String {
        val domain = query["domain"].orEmpty()
        if (domain.isBlank()) {
            return json(mapOf("error" to "domain required (?domain=name.rbx)"))
        }
        val d = Store.normalizeDomain(domain)
            ?: return json(mapOf("error" to "bad domain '$domain'"))
        val prefix = "$d::"
        val all = com.cwbridge.android.data.UserFileStore.storeAll(context)
        val pairs = all.entries
            .filter { it.key.startsWith(prefix) }
            .map { e -> e.key.removePrefix(prefix) to e.value }
            .sortedBy { it.first }
        val keys = pairs.map { (key, value) ->
            mapOf(
                "key" to key,
                "value" to value,
                "bytes" to value.toByteArray(Charsets.UTF_8).size,
            )
        }
        return json(
            mapOf(
                "domain" to d,
                "keys" to keys,
                "used" to Store.formatBytes(store.usageOf(d)),
                "limit" to Store.formatBytes(store.limitOf(d)),
                "limitBytes" to store.limitOf(d),
            ),
        )
    }'''
    if old not in t:
        if "sortedBy { it.first }" in t:
            print("already fixed")
            return
        raise SystemExit("storeKeysJson block not found")
    t = t.replace(old, new, 1)
    if "import kotlin.text.Charsets" not in t and "Charsets.UTF_8" in t:
        t = t.replace(
            "import java.net.URLDecoder",
            "import java.net.URLDecoder\nimport kotlin.text.Charsets",
            1,
        )
    P.write_text(t)
    print("fixed")

if __name__ == "__main__":
    main()
