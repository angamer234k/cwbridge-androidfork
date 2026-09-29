#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"

def main() -> None:
    t = P.read_text()
    bad = 'LogBuffer.i("Server", "auto-open domain set to \'${one.ifEmpty { "(cleared)" }}\'")'
    # actual file content uses nested doubles — match flexibly
    old = 'LogBuffer.i("Server", "auto-open domain set to \'${one.ifEmpty { "(cleared)" }}\'")'
    # From the raw file we saw:
    old2 = '''LogBuffer.i("Server", "auto-open domain set to '${one.ifEmpty { "(cleared)" }}'")'''
    new = '''val label = if (one.isEmpty()) "(cleared)" else one
        LogBuffer.i("Server", "auto-open domain set to '$label'")'''
    if old2 in t:
        t = t.replace(old2, new, 1)
        P.write_text(t)
        print("fixed log line")
    elif "auto-open domain set to" in t and "label" not in t[t.find("setAutoDomainJson"):t.find("setAutoDomainJson")+800]:
        # broader replace
        import re
        t2, n = re.subn(
            r'LogBuffer\.i\("Server", "auto-open domain set to \'\$\{one\.ifEmpty \{ "\(cleared\)" \}\}\'"\)',
            'val label = if (one.isEmpty()) "(cleared)" else one\n        LogBuffer.i("Server", "auto-open domain set to \'$label\'")',
            t,
            count=1,
        )
        if n:
            P.write_text(t2)
            print("fixed via regex")
        else:
            # show snippet
            i = t.find("auto-open domain")
            print("WARN not matched", repr(t[i:i+120]))
    else:
        print("already ok or missing")

if __name__ == "__main__":
    main()
