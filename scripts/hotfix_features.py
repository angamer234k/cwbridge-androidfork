#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/bridge/ScreenOcr.kt"

def main() -> None:
    t = P.read_text()
    t2 = t.replace(
        "TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)",
        "TextRecognition.getClient(TextRecognizerOptions.Builder().build())",
    )
    # Ensure import for Builder path still uses latin options
    if "import com.google.mlkit.vision.text.latin.TextRecognizerOptions" not in t2:
        t2 = t2.replace(
            "import com.google.mlkit.vision.text.TextRecognition",
            "import com.google.mlkit.vision.text.TextRecognition\nimport com.google.mlkit.vision.text.latin.TextRecognizerOptions",
            1,
        )
    P.write_text(t2)
    print("mlkit client fixed")

if __name__ == "__main__":
    main()
