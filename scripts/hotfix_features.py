#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
P = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"


def main():
    t = P.read_text()
    n = 0
    # In the .kt file JS string uses \\" for a quote inside the generated HTML attribute.
    # Broken:  onclick=\"deleteDomainKey(" + arg(key) + ")\">
    #          → HTML onclick="deleteDomainKey("key")"  (parse error)
    # Fixed:   onclick='deleteDomainKey(' + arg(key) + ')'>
    #          → HTML onclick='deleteDomainKey("key")'

    reps = [
        (
            'onclick=\"deleteDomainKey(" + arg(key) + ")\">',
            "onclick='deleteDomainKey(" + arg(key) + ")'>",
        ),
        (
            'onclick=\"clearDomain(" + arg(d.domain) + ")\">',
            "onclick='clearDomain(" + arg(d.domain) + ")'>",
        ),
        (
            "onclick=\"saveDomainKey(" + arg(key) + ",'" + id + "')\">",
            "onclick='saveDomainKey(" + arg(key) + ",\\'" + id + "\\')'>",
        ),
    ]
    for a, b in reps:
        c = t.count(a)
        if c:
            t = t.replace(a, b)
            n += c
            print("replaced", c, a[:50])
        else:
            print("miss", repr(a[:60]))

    if n == 0:
        raise SystemExit("no replacements")
    P.write_text(t)
    print("ok", n, P.stat().st_size)


if __name__ == "__main__":
    main()
