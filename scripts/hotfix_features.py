#!/usr/bin/env python3
from pathlib import Path

p = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
t = p.read_text()

# Fix broken multi-line string from bad \\n expansion
import re
pat = r'val cleaned = out\.replace\("[\s\S]*?"\, ""\)\.replace\("[\s\S]*?"\, ""\)\.replace\(" "\, ""\)'
repl = 'val cleaned = out.replace("\\n", "").replace("\\r", "").replace(" ", "")'
t2, n = re.subn(pat, repl, t, count=1)
if n != 1:
    # try exact broken form
    if 'val cleaned = out.replace(' in t:
        start = t.find('val cleaned = out.replace(')
        end = t.find('val filtered', start)
        if start > 0 and end > start:
            t = t[:start] + 'val cleaned = out.replace("\\n", "").replace("\\r", "").replace(" ", "")\n                ' + t[end:]
            print('fixed via span')
        else:
            raise SystemExit('cannot find cleaned span')
    else:
        raise SystemExit('cleaned not found')
else:
    t = t2
    print('fixed via regex')

p.write_text(t)
print('BridgeControl', p.stat().st_size)
