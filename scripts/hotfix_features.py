#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"

def main() -> None:
    t = P.read_text()
    t2 = t.replace("UserFileStore.settingsGet(", "UserFileStore.getSetting(")
    t2 = t2.replace("UserFileStore.settingsPut(", "UserFileStore.putSetting(")
    # getSetting returns String? — handle null
    t2 = t2.replace(
        '''            val raw = com.cwbridge.android.data.UserFileStore.getSetting(
                context.applicationContext,
                "auto_open_domains",
                "",
            )
            raw.lines()''',
        '''            val raw = com.cwbridge.android.data.UserFileStore.getSetting(
                context.applicationContext,
                "auto_open_domains",
                "",
            ) ?: ""
            raw.lines()''',
    )
    if t2 == t:
        print("no changes or already fixed")
    else:
        P.write_text(t2)
        print("fixed settings API names")

if __name__ == "__main__":
    main()
