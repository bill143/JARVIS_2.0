"""Per-frame object identification (pillar 2: object identification)."""

from jarvis_vision.analyzer import analyze_image_bytes
from jarvis_vision.objects import identify_objects
from jarvis_vision.pngutil import synthetic_frame, write_gray_png


def test_identify_objects_returns_labeled_regions():
    frame = synthetic_frame(seed=1)
    result = identify_objects(frame)
    assert result["objects"], "object identification must return at least one object"
    for o in result["objects"]:
        assert "label" in o and "confidence" in o and "bbox" in o
    assert result["engine"] in ("heuristic", "heuristic-fallback")


def test_text_block_object_when_ocr_text_present():
    frame = synthetic_frame(seed=2)
    result = identify_objects(frame, ocr_text="INVOICE 12345")
    assert any(o["label"] == "text block" for o in result["objects"])


def test_analyzer_includes_objects_per_frame():
    png = write_gray_png(120, 40, lambda x, y: 255 if (x // 10) % 2 else 0)
    analysis = analyze_image_bytes(png, source="stream")
    assert analysis.objects, "each analyzed frame must carry object identifications"
    assert analysis.object_engine
    assert "objects:" in analysis.caption


def test_objects_deterministic():
    frame = synthetic_frame(seed=3)
    a = identify_objects(frame)
    b = identify_objects(frame)
    assert a == b  # deterministic for reproducible tests
