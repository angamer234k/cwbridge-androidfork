#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"

def main() -> None:
    t = P.read_text()
    t2 = t.replace(
        "            dispatch(out, method, path, body)",
        "            dispatch(out, method, path, query, body)",
        1,
    )
    t2 = t2.replace(
        "    private fun dispatch(out: OutputStream, method: String, path: String, body: String) {",
        "    private fun dispatch(out: OutputStream, method: String, path: String, query: Map<String, String>, body: String) {",
        1,
    )
    if t2 == t:
        raise SystemExit("no changes — patterns not found")
    P.write_text(t2)
    print("dispatch now receives query")

if __name__ == "__main__":
    main()
