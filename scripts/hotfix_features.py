#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"

def main() -> None:
    t = P.read_text()
    old = '''            if (!ctrl) { results += "$d: Ctrl+T failed"; continue }'''
    # also multi-line form
    old2 = '''            if (!ctrl) {
                results += "$d: Ctrl+T failed"
                continue
            }'''
    new = '''            if (!ctrl) {
                // CatWeb tab bar "+" (wiki: opens new tab). Adjust if needed.
                val plusX = 92f
                val plusY = 4f
                val tapped = svc?.clickAtPercent(plusX, plusY) == true
                LogBuffer.w("Control", "Ctrl+T failed — tapping + @$plusX%,$plusY% ok=$tapped")
                if (!tapped) {
                    results += "$d: Ctrl+T and + tap failed"
                    continue
                }
            }'''
    if old2 in t:
        t = t.replace(old2, new, 1)
    elif old in t:
        t = t.replace(old, new, 1)
    elif "tapping +" in t:
        print("already patched")
        return
    else:
        raise SystemExit("ctrl fail block not found")
    P.write_text(t)
    print("openDomains + fallback ok")

if __name__ == "__main__":
    main()
