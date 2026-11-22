"""OCR via pytesseract with Windows auto-detection and a graceful fallback."""

from __future__ import annotations

import os
import shutil
from pathlib import Path

_WINDOWS_TESSERACT_PATHS = [
    r"C:\Program Files\Tesseract-OCR\tesseract.exe",
    r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
    str(Path.home() / "AppData" / "Local" / "Programs" / "Tesseract-OCR" / "tesseract.exe"),
    str(Path.home() / "AppData" / "Local" / "Tesseract-OCR" / "tesseract.exe"),
]


def find_tesseract(override: str = "") -> str | None:
    """Resolve the tesseract binary: explicit override > TESSERACT_CMD > PATH > common installs."""
    for candidate in (override, os.environ.get("TESSERACT_CMD", "")):
        if candidate and Path(candidate).exists():
            return candidate
    on_path = shutil.which("tesseract")
    if on_path:
        return on_path
    for candidate in _WINDOWS_TESSERACT_PATHS:
        if Path(candidate).exists():
            return candidate
    return None


def ocr_image(image_bytes: bytes, tesseract_cmd: str = "") -> dict:
    """Returns {text, engine, note}. engine: 'tesseract' | 'fallback'."""
    binary = find_tesseract(tesseract_cmd)
    if binary:
        try:
            import io

            import pytesseract
            from PIL import Image

            pytesseract.pytesseract.tesseract_cmd = binary
            image = Image.open(io.BytesIO(image_bytes))
            text = pytesseract.image_to_string(image)
            return {"text": text.strip(), "engine": "tesseract", "note": ""}
        except ImportError:
            return {"text": "", "engine": "fallback", "note": "pytesseract/Pillow not installed"}
        except Exception as exc:
            return {"text": "", "engine": "fallback", "note": f"OCR failed: {type(exc).__name__}: {exc}"}
    return {
        "text": "",
        "engine": "fallback",
        "note": "tesseract binary not found (install Tesseract-OCR or set TESSERACT_CMD)",
    }
