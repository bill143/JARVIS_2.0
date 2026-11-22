"""E2E: image upload -> analysis/OCR -> structured response (fallback-safe)."""

import base64

from jarvis_vision.pngutil import write_gray_png


def test_image_upload_ocr_pipeline(client):
    png = write_gray_png(120, 40, lambda x, y: 255 if (x // 10) % 2 else 0)
    resp = client.post("/vision/analyze", json={
        "image_b64": base64.b64encode(png).decode(),
        "source": "upload",
        "session_id": "e2e-vision",
    })
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["ok"] is True
    assert data["width"] == 120
    assert data["height"] == 40
    # OCR runs for real when tesseract is installed; otherwise the fallback engine reports itself.
    assert data["ocr_engine"] in ("tesseract", "fallback")
    if data["ocr_engine"] == "fallback":
        assert data["note"]  # informative degradation, never silent
    assert data["caption"]

    logs = client.get("/sessions/e2e-vision/logs")
    kinds = [e["kind"] for e in logs.json()["data"]["events"]]
    assert "vision.analysis" in kinds


def test_vision_rejects_bad_payloads(client):
    assert client.post("/vision/analyze", json={}).status_code == 400
    resp = client.post("/vision/analyze", json={"image_b64": "!!!not-base64!!!"})
    assert resp.status_code == 400


def test_desktop_runtime_sends_frame_analysis(client, settings):
    """Desktop runtime path: capture a frame (synthetic fallback) and send it."""
    from jarvis_vision.capture import CaptureService

    frame = CaptureService(sample_rate_hz=2.0).capture_frame()
    assert frame["ok"] is True
    assert frame["source"] in ("webcam", "synthetic")
    resp = client.post("/vision/analyze", json={
        "image_b64": frame["png_b64"], "source": f"desktop-{frame['source']}", "session_id": "desktop",
    })
    assert resp.status_code == 200
    assert resp.json()["data"]["ok"] is True


def test_webcam_snapshot_tool(client):
    resp = client.post("/tools/execute", json={"tool": "webcam_snapshot", "arguments": {}})
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["status"] == "ok"
    assert data["output"]["source"] in ("webcam", "synthetic")
    assert data["output"]["analysis"]["ok"] is True
