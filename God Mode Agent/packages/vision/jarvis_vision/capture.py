"""OpenCV webcam capture service with configurable sampling and graceful fallback."""

from __future__ import annotations

import base64
import time

from jarvis_shared.logging import get_logger, log_event
from jarvis_vision.pngutil import synthetic_frame

logger = get_logger("jarvis.vision.capture")


class CaptureService:
    """Captures webcam frames via OpenCV; degrades to deterministic synthetic
    frames with an informative note when no camera / no cv2 is available."""

    def __init__(self, device_index: int = 0, sample_rate_hz: float = 1.0):
        self.device_index = device_index
        self.sample_rate_hz = max(0.05, float(sample_rate_hz))
        self._last_capture = 0.0
        self._frame_counter = 0

    @property
    def min_interval_seconds(self) -> float:
        return 1.0 / self.sample_rate_hz

    def wait_for_next_slot(self) -> None:
        elapsed = time.monotonic() - self._last_capture
        remaining = self.min_interval_seconds - elapsed
        if remaining > 0:
            time.sleep(remaining)

    def capture_frame(self) -> dict:
        self._last_capture = time.monotonic()
        self._frame_counter += 1
        note = ""
        try:
            import cv2  # optional dependency

            cap = cv2.VideoCapture(self.device_index)
            try:
                if cap.isOpened():
                    ok, frame = cap.read()
                    if ok and frame is not None:
                        success, buf = cv2.imencode(".png", frame)
                        if success:
                            h, w = frame.shape[:2]
                            log_event(logger, "capture.webcam", width=w, height=h)
                            return {
                                "ok": True,
                                "source": "webcam",
                                "width": int(w),
                                "height": int(h),
                                "png_b64": base64.b64encode(buf.tobytes()).decode(),
                                "note": "",
                            }
                    note = "camera opened but frame read failed"
                else:
                    note = f"no camera available at device index {self.device_index}"
            finally:
                cap.release()
        except ImportError:
            note = "opencv-python not installed; using synthetic frame"
        except Exception as exc:
            note = f"webcam capture error: {type(exc).__name__}: {exc}"

        png = synthetic_frame(seed=self._frame_counter)
        log_event(logger, "capture.synthetic", reason=note)
        return {
            "ok": True,
            "source": "synthetic",
            "width": 96,
            "height": 72,
            "png_b64": base64.b64encode(png).decode(),
            "note": note,
        }
