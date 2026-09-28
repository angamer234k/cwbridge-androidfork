#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/MainActivity.kt"

def main() -> None:
    t = P.read_text()
    if "refreshBridgeUi()" in t:
        t = t.replace("refreshBridgeUi()", "refreshUi()")
        P.write_text(t)
        print("fixed refreshUi")
    else:
        print("already ok")

if __name__ == "__main__":
    main()
