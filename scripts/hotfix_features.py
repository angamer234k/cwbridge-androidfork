#!/usr/bin/env python3
"""Fix Store.parseSize: unit powers were 10/20/30 with 1024.pow → overflow → null."""
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/data/Store.kt"

NEW_PARSE = r'''        fun parseSize(input: String): Long? {
            // Accept: 69420 | 500KB | 500 KB | 1.5mb | 2GiB | 1gb | 8kib | 100bits
            val s = input.trim().lowercase()
                .replace("_", "")
                .replace(" ", "")
                .replace(",", "")
            if (s.isEmpty()) return null

            val m = Regex(
                "^([0-9]*\\.?[0-9]+)(k|m|g|t)?(i)?(b|bit|bits)?$",
            ).matchEntire(s) ?: return null

            val amount = m.groupValues[1].toDoubleOrNull() ?: return null
            if (amount < 0) return null

            val prefix = m.groupValues[2]           // k/m/g/t or ""
            val binary = m.groupValues[3] == "i"    // KiB style
            val suffix = m.groupValues[4]           // b / bit / bits / ""

            val isBits = suffix == "bit" || suffix == "bits"
            // Bare number with no unit → bytes
            val unitPower = when (prefix) {
                "k" -> 1
                "m" -> 2
                "g" -> 3
                "t" -> 4
                else -> 0
            }
            val radix = if (binary) 1024.0 else 1024.0 // we use 1024 for both KB and KiB
            val multiplier = radix.pow(unitPower.toDouble())
            var bytes = amount * multiplier
            if (isBits) bytes /= 8.0

            if (bytes < 0 || bytes > Long.MAX_VALUE.toDouble()) return null
            return bytes.toLong()
        }
'''

def main() -> None:
    t = P.read_text()
    start = t.find("        fun parseSize(input: String): Long? {")
    if start < 0:
        raise SystemExit("parseSize not found")
    end = t.find("        fun formatBytes", start)
    if end < 0:
        raise SystemExit("formatBytes not found")
    t = t[:start] + NEW_PARSE + "\n" + t[end:]
    P.write_text(t)
    print("parseSize fixed")

if __name__ == "__main__":
    main()
