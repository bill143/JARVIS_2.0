"""Webcam snapshot tool — captures a frame (or synthetic fallback) and analyzes it."""

from __future__ import annotations

import base64


def webcam_snapshot(tesseract_cmd: str = "") -> dict:
    from jarvis_vision.analyzer import analyze_image_bytes
    from jarvis_vision.capture import CaptureService

    service = CaptureService()
    frame = service.capture_frame()
    analysis = {}
    if frame.get("png_b64"):
        try:
            analysis = analyze_image_bytes(
                base64.b64decode(frame["png_b64"]), source=frame.get("source", "webcam"), tesseract_cmd=tesseract_cmd
            ).model_dump()
        except Exception as exc:
            analysis = {"ok": False, "note": f"analysis failed: {exc}"}
    return {
        "source": frame.get("source"),
        "width": frame.get("width"),
        "height": frame.get("height"),
        "note": frame.get("note", ""),
        "analysis": analysis,
    }
