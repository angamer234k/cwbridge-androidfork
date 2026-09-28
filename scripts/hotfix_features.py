#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"

def main() -> None:
    t = P.read_text()

    # Fix broken esc() map (entities got eaten)
    bad_esc = '''function esc(s){
  return String(s == null ? "" : s).replace(/[&<>"\\']/g, function(c){
    return ({"&":"&","<":"<",">":">","\\"":""","\\'":"&#39;"})[c];
  });
}'''
    # Try several possible corrupted forms
    import re
    t2, n = re.subn(
        r"function esc\(s\)\{.*?\n\}",
        '''function esc(s){
  return String(s == null ? "" : s).replace(/[&<>"']/g, function(c){
    return ({'&':'&','<':'<','>':'>','"':'"',"'":'&#39;'})[c];
  });
}''',
        t,
        count=1,
        flags=re.S,
    )
    if n == 0:
        raise SystemExit("esc not found")
    t = t2
    print("esc fixed")

    # Fix onclick=ctl(\'xxx\') -> onclick=ctl('xxx')
    t = t.replace("ctl(\\\'", "ctl('")
    t = t.replace("\\')", "')")
    # Also form with single backslash in file
    t = t.replace(r"ctl(\'", "ctl('")
    t = t.replace(r"\')", "')")

    # Service button generators may still need \' inside JS strings that build HTML.
    # Those live inside JS string literals like: onclick=\"runService(\'" + id + "\')\"
    # which is correct for JS. Don't break those — only HTML attribute ones were wrong.

    P.write_text(t)
    print("onclick quotes fixed")

if __name__ == "__main__":
    main()
