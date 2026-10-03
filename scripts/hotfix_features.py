#!/usr/bin/env python3
import re
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"


def main():
    t = P.read_text()
    t, n1 = re.subn(
        r'onclick=\\"deleteDomainKey\(" \+ arg\(key\) \+ "\)\\">',
        "onclick='deleteDomainKey(\" + arg(key) + \")'>",
        t,
    )
    t, n2 = re.subn(
        r'onclick=\\"clearDomain\(" \+ arg\(d\.domain\) \+ "\)\\">',
        "onclick='clearDomain(\" + arg(d.domain) + \")'>",
        t,
    )
    t, n3 = re.subn(
        r'onclick=\\"saveDomainKey\(" \+ arg\(key\) \+ ",\'" \+ id \+ "\'\)\\">',
        "onclick='saveDomainKey(\" + arg(key) + \",\\'' + id + \"\\'')'>",
        t,
    )
    print("n", n1, n2, n3)
    if n1 + n2 == 0:
        raise SystemExit("no match")
    P.write_text(t)
    print("ok", P.stat().st_size)


if __name__ == "__main__":
    main()
