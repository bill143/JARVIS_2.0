"""Structured visual analysis: dimensions, brightness, OCR, heuristic caption."""

from __future__ import annotations

from jarvis_shared.logging import get_logger, span
from jarvis_shared.schemas import VisionAnalysis
from jarvis_vision.ocr import ocr_image
from jarvis_vision.pngutil import image_dimensions, mean_gray_of_png

logger = get_logger("jarvis.vision.analyzer")


def _dims_and_brightness(data: bytes) -> tuple[int | None, int | None, float | None]:
    try:
        import io

        from PIL import Image

        img = Image.open(io.BytesIO(data))
        gray = img.convert("L")
        histogram = gray.histogram()
        total = sum(histogram)
        brightness = round(sum(i * c for i, c in enumerate(histogram)) / total, 2) if total else None
        return img.width, img.height, brightness
    except ImportError:
        dims = image_dimensions(data)
        return (dims[0] if dims else None, dims[1] if dims else None, mean_gray_of_png(data))
    except Exception:
        dims = image_dimensions(data)
        return (dims[0] if dims else None, dims[1] if dims else None, None)


def analyze_image_bytes(data: bytes, source: str = "upload", tesseract_cmd: str = "") -> VisionAnalysis:
    """Analyze an image and return a structured VisionAnalysis event payload."""
    if not data:
        return VisionAnalysis(ok=False, source=source, note="empty image payload")
    with span(logger, "vision.analyze", source=source, bytes=len(data)):
        width, height, brightness = _dims_and_brightness(data)
        ocr = ocr_image(data, tesseract_cmd=tesseract_cmd)
        caption_bits = []
        if width and height:
            caption_bits.append(f"{width}x{height} image")
        else:
            caption_bits.append("image of unknown dimensions")
        if brightness is not None:
            caption_bits.append("bright" if brightness > 170 else "dark" if brightness < 85 else "medium brightness")
        if ocr["text"]:
            caption_bits.append(f"contains text: {ocr['text'][:80]}")
        return VisionAnalysis(
            ok=True,
            source=source,
            width=width,
            height=height,
            mean_brightness=brightness,
            ocr_text=ocr["text"],
            ocr_engine=ocr["engine"],
            caption=", ".join(caption_bits),
            note=ocr["note"],
        )
