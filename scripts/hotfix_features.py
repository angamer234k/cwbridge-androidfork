#!/usr/bin/env python3
"""Replace streamed pm install with ADB sync push + pm install (TECNO-safe)."""
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "helper/src/main/java/com/cwbridge/helper/OtgAdbPusher.kt"

NEW_EXEC = r'''    private fun execInstall(conn: AdbConnection, apk: File, log: (String) -> Unit): String {
        // Streaming "cmd package install -S" often stalls after the first 4KB on
        // TECNO / some OEM OTG hosts. Push via sync: then pm install from path.
        val remote = "/data/local/tmp/cwbridge-push.apk"
        log("Push via sync: → $remote")
        pushViaSync(conn, apk, remote, log)
        log("Running pm install -r -t…")
        val out = shell(conn, "pm install -r -t \"$remote\"", log)
        shell(conn, "rm -f \"$remote\"", log)
        return out
    }

    /** ADB sync protocol: SEND path,mode → DATA chunks → DONE → OKAY/FAIL */
    private fun pushViaSync(conn: AdbConnection, apk: File, remotePath: String, log: (String) -> Unit) {
        val stream = conn.open("sync:")
        try {
            fun le32(v: Int): ByteArray = byteArrayOf(
                (v and 0xff).toByte(),
                ((v shr 8) and 0xff).toByte(),
                ((v shr 16) and 0xff).toByte(),
                ((v shr 24) and 0xff).toByte(),
            )
            fun writeId(id: String, payload: ByteArray) {
                require(id.length == 4)
                stream.write(id.toByteArray(StandardCharsets.UTF_8) + le32(payload.size) + payload)
            }
            val pathMode = "$remotePath,0644".toByteArray(StandardCharsets.UTF_8)
            writeId("SEND", pathMode)

            val buf = ByteArray(65536)
            var sent = 0L
            val size = apk.length()
            var lastPct = -1
            apk.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    writeId("DATA", chunk)
                    sent += n
                    val pct = if (size > 0) ((sent * 100) / size).toInt() else 100
                    if (pct != lastPct && (pct % 5 == 0 || pct == 100)) {
                        lastPct = pct
                        log("  … $pct% ($sent / $size)")
                    }
                }
            }
            writeId("DONE", le32((System.currentTimeMillis() / 1000L).toInt()))

            // response: OKAY/FAIL + 4-byte len + optional message
            val hdr = stream.read() ?: throw IllegalStateException("sync: no response")
            if (hdr.size < 8) throw IllegalStateException("sync: short response (${hdr.size})")
            val status = String(hdr, 0, 4, StandardCharsets.UTF_8)
            if (status != "OKAY") {
                val msg = if (hdr.size > 8) String(hdr, 8, hdr.size - 8, StandardCharsets.UTF_8) else status
                throw IllegalStateException("sync push failed: $msg")
            }
            log("  sync OK ($sent bytes)")
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
    }
'''

def main() -> None:
    t = P.read_text()
    if "pushViaSync" in t:
        print("already has pushViaSync")
        return
    start = t.find("    private fun execInstall(conn: AdbConnection, apk: File, log: (String) -> Unit): String {")
    if start < 0:
        raise SystemExit("execInstall not found")
    # find the next function at same indent after execInstall body
    end_marker = "    private fun shell(conn: AdbConnection, cmd: String, log: (String) -> Unit): String {"
    end = t.find(end_marker, start)
    if end < 0:
        raise SystemExit("shell() after execInstall not found")
    t = t[:start] + NEW_EXEC + "\n" + t[end:]
    P.write_text(t)
    print("replaced execInstall with sync push + pm install")

if __name__ == "__main__":
    main()
