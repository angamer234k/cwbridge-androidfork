"""Stage 1: move the flat com.cwbridge.android package into subpackages.

Behaviour-free: files move, package lines change, cross-package imports are
added. No identifiers, no logic, no resources are touched.

Classes referenced from AndroidManifest.xml or from an XML resource by name
(`.TapService`, `.MainActivity`, ...) must stay in the ROOT package, because
namespace = com.cwbridge.android, so a dot-relative name resolves against the
root only. Those are deliberately excluded from the move.
"""
import os
import re
import sys

SRC = "app/src/main/java/com/cwbridge/android"
ROOT_PKG = "com.cwbridge.android"

# file stem -> subpackage
MOVE = {
    # bridge: logcat -> status
    "bridge": ["AntiDisconnect", "BridgeControl", "BridgeNotifier", "BridgeStatus",
               "CatWebTracker", "LogBuffer", "LogcatReader", "RecentLogLines",
               "RobloxLogBuffer"],
    "engine": ["ExecutionEngine", "InvokeEngine", "ServiceAdapter"],
    "data":   ["ServiceConverters", "ServiceDao", "ServiceDatabase", "ServiceEntity",
               "ServiceModels", "Store", "VarStore"],
    "server": ["LocalHttpServer", "ServerAuth", "WebUi"],
}

# Top-level declarations we can import.
DECL = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+|enum\s+|annotation\s+|value\s+|inline\s+|fun\s+)*"
    r"(?:class|object|interface|typealias)\s+([A-Za-z_]\w*)"
    r"|^(?:public\s+|internal\s+)?(?:const\s+)?(?:val|var)\s+([A-Za-z_]\w*)"
    r"|^(?:public\s+|internal\s+)?(?:suspend\s+)?fun\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)",
    re.M)

# a reference to a name in running code (comments and string literals removed)
STR_OR_COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/|\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'", re.S)


def decls(text):
    """Top-level declaration names in a Kotlin file."""
    names = set()
    for m in DECL.finditer(text):
        for g in m.groups():
            if g:
                names.add(g)
    return names


def code_only(text):
    return STR_OR_COMMENT.sub(" ", text)


def set_package(text, pkg):
    new, n = re.subn(r"^package\s+[\w.]+", "package " + pkg, text, count=1, flags=re.M)
    if n != 1:
        raise SystemExit("could not rewrite package line")
    return new


def fix_imports(text, own_pkg, name_to_pkg):
    """Add an import for every name used from another package."""
    body = code_only(text)
    own_decls = decls(text)
    existing = re.findall(r"^import\s+([\w.]+)", text, re.M)
    have = set(existing)
    wanted = set()
    for name, pkg in name_to_pkg.items():
        if pkg == own_pkg:
            continue
        # A file that declares the name itself already resolves it locally;
        # importing the other package's version would clash.
        if name in own_decls:
            continue
        # word-boundary reference, and not merely the file's own declaration
        if re.search(r"(?<![\w.])" + re.escape(name) + r"(?![\w])", body):
            fq = pkg + "." + name
            if fq not in have:
                wanted.add(fq)
    # R / BuildConfig are generated into the root package
    if own_pkg != ROOT_PKG:
        if re.search(r"(?<![\w.])R\.", body):
            wanted.add(ROOT_PKG + ".R")
        if re.search(r"(?<![\w.])BuildConfig\.", body):
            wanted.add(ROOT_PKG + ".BuildConfig")
    if not wanted:
        return text, 0

    allimports = sorted(set(existing) | wanted)
    lines = text.split("\n")
    idx = [i for i, l in enumerate(lines) if l.startswith("import ")]
    if idx:
        lo, hi = idx[0], idx[-1]
        block = ["import " + i for i in allimports]
        lines[lo:hi + 1] = block
    else:
        pi = next(i for i, l in enumerate(lines) if l.startswith("package "))
        lines[pi + 1:pi + 1] = [""] + ["import " + i for i in allimports]
    return "\n".join(lines), len(wanted)


def main():
    # package of every file, before and after the move
    pkg_of = {}
    decl_map = {}
    for f in sorted(os.listdir(SRC)):
        if not f.endswith(".kt"):
            continue
        p = os.path.join(SRC, f)
        t = open(p, encoding="utf-8").read()
        stem = f[:-3]
        sub = next((s for s, names in MOVE.items() if stem in names), None)
        pkg_of[stem] = ROOT_PKG + ("." + sub if sub else "")
        for d in decls(t):
            decl_map.setdefault(d, pkg_of[stem])

    if "--check" in sys.argv:
        # After the move the top-level listing no longer sees the subpackages,
        # so rebuild the map by walking the whole tree and reading each file's
        # own package line. A check built from a stale map silently "passes".
        decl_map = {}
        for root, _, files in os.walk(SRC):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                t = open(os.path.join(root, f), encoding="utf-8").read()
                pkg = re.search(r"^package\s+([\w.]+)", t, re.M).group(1)
                for d in decls(t):
                    decl_map.setdefault(d, pkg)
        print(f"checking {len(decl_map)} declarations across the tree")
        return check(pkg_of, decl_map)

    for sub, names in MOVE.items():
        os.makedirs(os.path.join(SRC, sub), exist_ok=True)

    report = []
    touched = []
    for sub, names in MOVE.items():
        for stem in names:
            src = os.path.join(SRC, stem + ".kt")
            dst = os.path.join(SRC, sub, stem + ".kt")
            text = open(src, encoding="utf-8", newline="").read()
            text = set_package(text, ROOT_PKG + "." + sub)
            text, n = fix_imports(text, ROOT_PKG + "." + sub, decl_map)
            os.remove(src)
            touched.append(dst)
            report.append((sub, stem, n))

    # files staying in the root also need imports for anything that moved
    for stem, pkg in sorted(pkg_of.items()):
        if pkg != ROOT_PKG:
            continue
        p = os.path.join(SRC, stem + ".kt")
        text = open(p, encoding="utf-8", newline="").read()
        text, n = fix_imports(text, ROOT_PKG, decl_map)
        touched.append(p)
        if n:
            report.append(("(root)", stem, n))

    # Splitting on "\n" leaves a bare \n on freshly inserted import lines while
    # the surrounding lines keep their \r, so normalise back to CRLF.
    for p in touched:
        raw = open(p, "rb").read()
        raw = raw.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
        open(p, "wb").write(raw)

    for sub, stem, n in report:
        print(f"{sub:8} {stem:22} +{n} import(s)")
    print(f"\n{len(report)} files updated")


def check(pkg_of, decl_map):
    """A missing import is a compile error; an unused one is only a warning."""
    missing, unused = [], []
    for root, _, files in os.walk(SRC):
        for f in sorted(files):
            if not f.endswith(".kt"):
                continue
            p = os.path.join(root, f)
            text = open(p, encoding="utf-8").read()
            pkg = re.search(r"^package\s+([\w.]+)", text, re.M).group(1)
            body = code_only(text)
            own = decls(text)
            have = set(re.findall(r"^import\s+([\w.]+)", text, re.M))
            stem = os.path.relpath(p, SRC)[:-3]

            for name, npkg in decl_map.items():
                if npkg == pkg or name in own:
                    continue
                if not re.search(r"(?<![\w.])" + re.escape(name) + r"(?![\w])", body):
                    continue
                if npkg + "." + name not in have:
                    missing.append(f"{stem}: uses {name} from {npkg} - no import")

            for imp in have:
                if not imp.startswith("com.cwbridge.android."):
                    continue
                nm = imp.rsplit(".", 1)[-1]
                if not re.search(r"(?<![\w.])" + re.escape(nm) + r"(?![\w])", body):
                    unused.append(f"{stem}: unused import {imp}")

    print(f"MISSING IMPORTS: {len(missing)}")
    for m in missing:
        print("  !! " + m)
    print(f"UNUSED IMPORTS: {len(unused)}")
    for u in unused:
        print("  .. " + u)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main() or 0)
