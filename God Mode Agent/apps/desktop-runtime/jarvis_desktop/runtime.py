"""Desktop runtime: captures webcam frames at the configured sample rate and
sends analysis requests to the backend. Degrades gracefully without camera/mic.

Ctrl+C is handled cleanly on Windows (KeyboardInterrupt in the sync sleep loop).
"""

from __future__ import annotations

import argparse
import sys

import httpx

from jarvis_shared.config import get_settings
from jarvis_shared.logging import get_logger, log_event
from jarvis_vision.capture import CaptureService

logger = get_logger("jarvis.desktop")


def check_microphone() -> str:
    try:
        import sounddevice  # optional dependency

        devices = [d["name"] for d in sounddevice.query_devices() if d.get("max_input_channels", 0) > 0]
        if devices:
            return f"microphone available: {devices[0]}"
        return "no input-capable audio devices found"
    except ImportError:
        return "sounddevice not installed - mic bridge disabled (voice still works via the web UI)"
    except Exception as exc:
        return f"microphone check failed: {type(exc).__name__}: {exc}"


def send_frame(client: httpx.Client, backend: str, frame: dict) -> dict:
    resp = client.post(
        f"{backend}/vision/analyze",
        json={"image_b64": frame["png_b64"], "source": f"desktop-{frame['source']}", "session_id": "desktop"},
        timeout=30.0,
    )
    resp.raise_for_status()
    return resp.json()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="JARVIS desktop runtime (camera/mic bridge)")
    parser.add_argument("--once", action="store_true", help="capture and send a single frame, then exit")
    parser.add_argument("--backend", default="", help="backend URL override")
    args = parser.parse_args(argv)

    settings = get_settings()
    backend = (args.backend or settings.backend_public_url).rstrip("/")
    capture = CaptureService(sample_rate_hz=settings.frame_sample_rate_hz)

    log_event(logger, "desktop.start", backend=backend, sample_rate_hz=capture.sample_rate_hz)
    log_event(logger, "desktop.mic", status=check_microphone())

    sent = 0
    try:
        with httpx.Client() as client:
            while True:
                frame = capture.capture_frame()
                try:
                    result = send_frame(client, backend, frame)
                    data = result.get("data", {})
                    log_event(
                        logger, "desktop.frame_analyzed",
                        source=frame["source"], width=frame.get("width"), height=frame.get("height"),
                        caption=data.get("caption", ""), ocr_engine=data.get("ocr_engine", ""),
                        note=frame.get("note", ""),
                    )
                except httpx.HTTPError as exc:
                    log_event(logger, "desktop.frame_failed", error=str(exc), backend=backend)
                sent += 1
                if args.once:
                    break
                capture.wait_for_next_slot()
    except KeyboardInterrupt:
        log_event(logger, "desktop.shutdown", reason="Ctrl+C", frames_sent=sent)
        return 0
    log_event(logger, "desktop.done", frames_sent=sent)
    return 0


if __name__ == "__main__":
    sys.exit(main())
