#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"

def main() -> None:
    t = P.read_text()
    if "fun stopBridgeWithError(message: String) {\n        try {" in t or "hooks?.stopBridgeWithError" in t:
        print("already has method")
        return
    needle = """    fun setHooks(h: Hooks?) {
        hooks = h
    }"""
    insert = """    fun setHooks(h: Hooks?) {
        hooks = h
    }

    fun stopBridgeWithError(message: String) {
        try {
            hooks?.stopBridgeWithError(message)
        } catch (t: Throwable) {
            LogBuffer.e("Control", "stopBridgeWithError: ${t.message}")
        }
    }"""
    if needle not in t:
        raise SystemExit("setHooks not found")
    t = t.replace(needle, insert, 1)
    P.write_text(t)
    print("stopBridgeWithError method added")

if __name__ == "__main__":
    main()
