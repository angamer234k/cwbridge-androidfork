#!/usr/bin/env python3
"""Fix broken onclick=\"fn(\"key\")\" in WebUi store/domain DB buttons."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
P = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"


def main():
    t = P.read_text()
    n = 0

    # Domain DB rows: Save + Del buttons
    old1 = (
        '"<button type=\\"button\\" onclick=\\"saveDomainKey(" + arg(key) + ",\'" + id + "\')\\">Save</button>" +\n'
        '          "<button type=\\"button\\" class=\\"danger\\" onclick=\\"deleteDomainKey(" + arg(key) + ")\\">Del</button></div>";'
    )
    # try without heavy escaping - read actual snippet
    if 'onclick=\"saveDomainKey(" + arg(key)' in t or "onclick=\"saveDomainKey(" + arg(key)" in t:
        pass

    # Flexible replacements
    pairs = [
        (
            'onclick=\"saveDomainKey(" + arg(key) + ",\'" + id + "\')\"',
            "onclick='saveDomainKey(" + arg(key) + ",\\'" + id + "\\')'",
        ),
        (
            'onclick=\"deleteDomainKey(" + arg(key) + ")\"',
            "onclick='deleteDomainKey(" + arg(key) + ")'",
        ),
        (
            'onclick=\"clearDomain(" + arg(d.domain) + ")\"',
            "onclick='clearDomain(" + arg(d.domain) + ")'",
        ),
    ]
    # In Kotlin source the backslashes appear as \\" for JS inside """
    # Actual file content from raw - check
    for a, b in [
        (
            'onclick=\\"saveDomainKey(" + arg(key) + ",\'" + id + "\')\\"',
            "onclick='saveDomainKey(" + arg(key) + ",\\'" + id + "\\')'",
        ),
        (
            'onclick=\\"deleteDomainKey(" + arg(key) + ")\\"',
            "onclick='deleteDomainKey(" + arg(key) + ")'",
        ),
        (
            'onclick=\\"clearDomain(" + arg(d.domain) + ")\\"',
            "onclick='clearDomain(" + arg(d.domain) + ")'",
        ),
    ]:
        if a in t:
            t = t.replace(a, b)
            n += 1
            print("fixed", a[:40])

    # Alternate: double-backslash patterns as stored in .kt PART strings
    # From raw github the JS is inside """.trimIndent() so we see:
    # onclick=\"deleteDomainKey(" + arg(key) + ")\">
    for a, b in [
        (
            'onclick=\"deleteDomainKey(" + arg(key) + ")\">',
            "onclick='deleteDomainKey(" + arg(key) + ")'>",
        ),
        (
            'onclick=\"clearDomain(" + arg(d.domain) + ")\">',
            "onclick='clearDomain(" + arg(d.domain) + ")'>",
        ),
        (
            'onclick=\"saveDomainKey(" + arg(key) + ",\'" + id + "\')\">',
            "onclick='saveDomainKey(" + arg(key) + ",\\'" + id + "\\')'>",
        ),
    ]:
        if a in t:
            t = t.replace(a, b)
            n += 1
            print("fixed2", a[:50])

    if n == 0:
        # dump nearby for debug
        i = t.find("deleteDomainKey(")
        print("NEAR", repr(t[i - 30 : i + 80]) if i > 0 else "none")
        i = t.find("clearDomain(")
        print("NEAR2", repr(t[i - 30 : i + 80]) if i > 0 else "none")
        raise SystemExit("no onclick patterns matched")

    P.write_text(t)
    print("WebUi store onclick fixed", n, P.stat().st_size)


if __name__ == "__main__":
    main()
