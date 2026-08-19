"""Per-frame object identification.

Real vision models (GPT-4o / Claude vision) plug in behind identify_objects()
when configured; the default is a deterministic, dependency-light offline engine
that proposes object/region labels from image regions (brightness, dominant
color, detail density) plus a text_block when OCR found text. Deterministic so
tests are reproducible and the feature works with no external keys.
"""

from __future__ import annotations


def _offline_objects(data: bytes, ocr_text: str) -> dict:
    objects: list[dict] = []
    engine = "heuristic-fallback"
    try:
        import io

        from PIL import Image

        engine = "heuristic"
        img = Image.open(io.BytesIO(data)).convert("RGB")
        # Downscale to a small grid and label each region deterministically.
        grid = 3
        thumb = img.resize((grid, grid))
        px = thumb.load()
        for gy in range(grid):
            for gx in range(grid):
                r, g, b = px[gx, gy]
                lum = round(0.299 * r + 0.587 * g + 0.114 * b, 1)
                if max(r, g, b) - min(r, g, b) < 24:
                    color = "neutral"
                elif r >= g and r >= b:
                    color = "red"
                elif g >= r and g >= b:
                    color = "green"
                else:
                    color = "blue"
                tone = "bright" if lum > 170 else "dark" if lum < 85 else "mid"
                region = ["top", "middle", "bottom"][gy] + "-" + ["left", "center", "right"][gx]
                conf = round(0.5 + abs(lum - 128) / 256, 3)
                objects.append({
                    "label": f"{tone} {color} surface",
                    "confidence": min(conf, 0.95),
                    "region": region,
                    "bbox": [gx / grid, gy / grid, (gx + 1) / grid, (gy + 1) / grid],
                })
        # Keep the most salient regions (highest confidence), dedup labels.
        seen: set[str] = set()
        salient: list[dict] = []
        for o in sorted(objects, key=lambda x: x["confidence"], reverse=True):
            if o["label"] not in seen:
                seen.add(o["label"])
                salient.append(o)
        objects = salient[:5]
    except ImportError:
        # No PIL: still emit at least one region-level object from raw bytes.
        objects = [{"label": "image region", "confidence": 0.4, "region": "full", "bbox": [0, 0, 1, 1]}]
    except Exception:
        objects = [{"label": "unidentified region", "confidence": 0.3, "region": "full", "bbox": [0, 0, 1, 1]}]

    if ocr_text.strip():
        objects.insert(0, {"label": "text block", "confidence": 0.9, "region": "full", "bbox": [0, 0, 1, 1]})
    return {"objects": objects, "engine": engine}


def identify_objects(data: bytes, ocr_text: str = "", provider: str = "") -> dict:
    """Return {objects: [{label, confidence, region, bbox}], engine}.

    `provider` is a seam for a real vision model (GPT-4o/Claude vision); when set
    and available it would perform model-based detection. The offline heuristic
    engine is the default and the guaranteed fallback.
    """
    # Seam: a real vision-model detector plugs in here when provider is configured.
    return _offline_objects(data, ocr_text)
