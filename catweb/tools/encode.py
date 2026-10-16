#!/usr/bin/env python3
"""Encode/decode strings with the CatWeb decoder table (SiteGPT §9.1)."""
TABLE = {
    " ": 1, "!": 2, '"': 3, "#": 4, "$": 5, "%": 6, "'": 7, "+": 9,
    ",": 11, "-": 12, ".": 13, "/": 14, "{": 15, "|": 16, "}": 17, "@": 18, "~": 19,
    "A": 29, "B": 31, "C": 32, "D": 33, "E": 34, "F": 35, "G": 36, "H": 37, "I": 38, "J": 39,
    "K": 41, "L": 42, "M": 43, "N": 44, "O": 45, "P": 46, "Q": 47, "R": 48, "S": 49, "T": 51,
    "U": 52, "V": 53, "W": 54, "X": 55, "Y": 56, "Z": 57, "[": 58, "\\": 59, "]": 61, "^": 62,
    "_": 63, "`": 64, "<": 65, ">": 66, "*": 67,
    "a": 68, "b": 69, "c": 71, "d": 72, "e": 73, "f": 74, "g": 75, "h": 76, "i": 77, "j": 78,
    "k": 79, "l": 81, "m": 82, "n": 83, "o": 84, "p": 85, "q": 86, "r": 87, "s": 88, "t": 89,
    "u": 91, "v": 92, "w": 93, "x": 94, "y": 95, "z": 96,
    "1": 97, "2": 98, "3": 99, "4": 111, "5": 112, "6": 113, "7": 114, "8": 115, "9": 116, "0": 117,
    ":": 25, ";": 26, "?": 27, "=": 28, "&": 24, "-": 12,
}
REV = {}
for k, v in TABLE.items():
    REV[v] = k

def encode(s: str) -> str:
    parts = []
    for ch in s:
        if ch in "()":
            raise SystemExit("cannot encode parentheses (code 8 is the token '()')")
        code = TABLE.get(ch)
        if code is None:
            raise SystemExit(f"no code for {ch!r}")
        parts.append(str(code))
    return "0" + "0".join(parts) + "0"

def decode(s: str) -> str:
    if not (s.startswith("0") and s.endswith("0")):
        raise SystemExit("encoded string must start and end with 0")
    body = s[1:-1]
    out = []
    i = 0
    while i < len(body):
        if body[i] == "0":
            i += 1
            continue
        matched = None
        for nlen in (3, 2, 1):
            chunk = body[i:i+nlen]
            if len(chunk) < nlen or "0" in chunk:
                continue
            code = int(chunk)
            if code in REV:
                matched = (nlen, REV[code])
                break
        if not matched:
            raise SystemExit(f"bad code near {body[i:i+6]!r}")
        nlen, ch = matched
        out.append(ch)
        i += nlen
        if i < len(body) and body[i] == "0":
            i += 1
    return "".join(out)

if __name__ == "__main__":
    import sys
    if len(sys.argv) < 3 or sys.argv[1] not in ("e", "d"):
        print("usage: encode.py e|d <string>")
        raise SystemExit(1)
    print(encode(sys.argv[2]) if sys.argv[1] == "e" else decode(sys.argv[2]))
