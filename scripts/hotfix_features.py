#!/usr/bin/env python3
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
t = p.read_text()

old_ms = (
    '.ms{font-family:"Material+Symbols+Rounded",sans-serif;font-weight:400;font-style:normal;\n'
    '  font-size:20px;line-height:1;vertical-align:middle;\n'
    '  font-variation-settings:"FILL" 0,"wght" 400,"GRAD" 0,"opsz" 24;\n'
    '  user-select:none}\n'
    '.ms.sm{font-size:18px}'
)

new_ms = (
    '.ms{\n'
    '  font-family:"Material Symbols Rounded",sans-serif;\n'
    '  font-weight:400;font-style:normal;font-size:20px;line-height:1;\n'
    '  vertical-align:middle;letter-spacing:normal;text-transform:none;\n'
    '  font-variation-settings:"FILL" 0,"wght" 400,"GRAD" 0,"opsz" 24;\n'
    '  font-feature-settings:"liga";-webkit-font-feature-settings:"liga";\n'
    '  user-select:none;display:inline-block;\n'
    '}\n'
    '.ms.sm{font-size:18px}\n'
    'h2 .ms,button .ms,a .ms,nav .ms{text-transform:none;letter-spacing:normal}'
)

if old_ms not in t:
    if "Material Symbols Rounded" in t and "text-transform:none" in t:
        print("already fixed")
    else:
        i = t.find(".ms{")
        raise SystemExit("ms block not found: " + repr(t[i : i + 200]))
else:
    t = t.replace(old_ms, new_ms, 1)
    print("ms css fixed")

p.write_text(t)
print("done", p.stat().st_size)
