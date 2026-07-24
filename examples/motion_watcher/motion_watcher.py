#!/usr/bin/env python3
"""Motion Watcher -- camera-side pixel-delta motion detector for the Jarvis vision pipeline.

Watches a local camera (or RTSP/video source) using frame differencing. When the
pixel delta between the current frame and a slowly-adapting reference frame
crosses a threshold, it captures the triggering frame and POSTs it to the Jarvis
backend's `POST /vision/motion` webhook (see
java/app/src/main/java/com/jarvis/app/WebServer.java), which owns everything
downstream: per-camera cooldown, face recognition, unknown-visitor persistence,
and the "what's your name" prompt. This script does no recognition itself -- its
only job is deciding *when* something moved and handing that one frame off.

Nothing is sent anywhere unless motion crosses the threshold, and nothing the
server does with a received frame happens unless the server has vision.motion
(and separately, vision.face) explicitly enabled -- both default to off. See
java/app/src/main/java/com/jarvis/app/VisionSettings.java for the server-side
settings this depends on.

Requires opencv-python, which is NOT installed by default:
    uv sync --extra vision-motion

Usage:
    python examples/motion_watcher/motion_watcher.py --help
    python examples/motion_watcher/motion_watcher.py \
        --server-url http://localhost:8080 --camera-id front-door \
        --webhook-secret "$JARVIS_VISION_MOTION_WEBHOOK_SECRET"
"""

from __future__ import annotations

import argparse
import base64
import socket
import sys
import threading
import time
from datetime import datetime, timezone

try:
    import cv2
except ImportError:
    print(
        "Error: opencv-python is not installed.\n"
        "Install it with:  uv sync --extra vision-motion",
        file=sys.stderr,
    )
    sys.exit(1)

try:
    import httpx
except ImportError:
    print(
        "Error: httpx is not installed. Install it with:  uv sync",
        file=sys.stderr,
    )
    sys.exit(1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Watch a camera for motion (pixel-delta thresholding) and forward "
            "triggering frames to the Jarvis /vision/motion webhook."
        ),
    )
    parser.add_argument(
        "--server-url",
        default="http://localhost:8080",
        help="Base URL of the Jarvis server (default: http://localhost:8080).",
    )
    parser.add_argument(
        "--camera-id",
        default=None,
        help="Identifier reported to the server (default: this machine's hostname).",
    )
    parser.add_argument(
        "--webhook-secret",
        default=None,
        help=(
            "Value for the X-Vision-Webhook-Secret header. Must match the "
            "server's JARVIS_VISION_MOTION_WEBHOOK_SECRET / "
            "vision.motion.webhookSecret setting. Omit only if the server has "
            "no secret configured (not recommended outside local testing)."
        ),
    )
    parser.add_argument(
        "--device",
        default="0",
        help=(
            "Camera source for cv2.VideoCapture: a webcam index (e.g. '0') or "
            "a stream URL (e.g. 'rtsp://...'). Default: '0'."
        ),
    )
    parser.add_argument(
        "--min-area",
        type=int,
        default=1500,
        help="Minimum contour area (pixels) to count as motion (default: 1500).",
    )
    parser.add_argument(
        "--delta-threshold",
        type=int,
        default=25,
        help="Per-pixel grayscale delta threshold, 0-255 (default: 25).",
    )
    parser.add_argument(
        "--client-cooldown-sec",
        type=float,
        default=5.0,
        help=(
            "Minimum seconds between frames this client will send, independent "
            "of the server's own per-camera cooldown (default: 5.0). Keeps a "
            "sustained motion event from being re-encoded and posted every "
            "single frame while it's still in progress."
        ),
    )
    parser.add_argument(
        "--jpeg-quality",
        type=int,
        default=85,
        help="JPEG encode quality, 1-100 (default: 85).",
    )
    parser.add_argument(
        "--timeout-sec",
        type=float,
        default=10.0,
        help="HTTP request timeout in seconds (default: 10.0).",
    )
    parser.add_argument(
        "--preview",
        action="store_true",
        help="Show a debug window with the motion mask (requires a display).",
    )
    return parser.parse_args()


def open_capture(device: str) -> cv2.VideoCapture:
    source = int(device) if device.isdigit() else device
    cap = cv2.VideoCapture(source)
    if not cap.isOpened():
        print(f"Error: could not open camera source {device!r}.", file=sys.stderr)
        sys.exit(1)
    return cap


def detect_motion(reference_gray, current_gray, delta_threshold: int, min_area: int) -> bool:
    delta = cv2.absdiff(reference_gray, current_gray)
    thresh = cv2.threshold(delta, delta_threshold, 255, cv2.THRESH_BINARY)[1]
    thresh = cv2.dilate(thresh, None, iterations=2)
    contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    return any(cv2.contourArea(c) >= min_area for c in contours)


def send_motion_event(
    client: httpx.Client,
    server_url: str,
    camera_id: str,
    webhook_secret: str | None,
    frame_bytes: bytes,
) -> None:
    """Runs on a background thread so a slow/unreachable server never stalls frame capture --
    mirroring the fire-and-forget handoff MotionEventService uses server-side."""
    headers = {}
    if webhook_secret:
        headers["X-Vision-Webhook-Secret"] = webhook_secret
    payload = {
        "cameraId": camera_id,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "imageBase64": base64.b64encode(frame_bytes).decode("ascii"),
        "snapshotUrl": "",
    }
    try:
        response = client.post(f"{server_url}/vision/motion", json=payload, headers=headers)
    except httpx.RequestError as exc:
        print(f"[motion-watcher] failed to reach {server_url}: {exc}", file=sys.stderr)
        return
    if response.status_code >= 300:
        print(
            f"[motion-watcher] server rejected event: {response.status_code} {response.text}",
            file=sys.stderr,
        )
    else:
        print(f"[motion-watcher] motion event sent ({response.status_code}).")


def main() -> None:
    args = parse_args()
    camera_id = args.camera_id or socket.gethostname()

    cap = open_capture(args.device)
    client = httpx.Client(timeout=args.timeout_sec)
    last_sent = 0.0
    reference_gray = None

    print(
        f"[motion-watcher] watching device={args.device!r} camera_id={camera_id!r} "
        f"-> {args.server_url}/vision/motion"
    )
    print("[motion-watcher] press Ctrl+C to stop.")

    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                print("[motion-watcher] frame grab failed, retrying...", file=sys.stderr)
                time.sleep(1.0)
                continue

            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            gray = cv2.GaussianBlur(gray, (21, 21), 0)

            if reference_gray is None:
                reference_gray = gray
                continue

            motion = detect_motion(reference_gray, gray, args.delta_threshold, args.min_area)

            if args.preview:
                try:
                    cv2.imshow("motion-watcher", gray)
                    cv2.waitKey(1)
                except cv2.error:
                    pass  # no display available; ignore

            # Slowly adapt the reference frame so lighting drift doesn't cause permanent
            # false positives, but skip the update while motion is active so a lingering
            # subject doesn't get absorbed into the background and stop being detected.
            if not motion:
                reference_gray = cv2.addWeighted(gray, 0.05, reference_gray, 0.95, 0)

            now = time.monotonic()
            if motion and now - last_sent >= args.client_cooldown_sec:
                encoded_ok, encoded = cv2.imencode(
                    ".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, args.jpeg_quality]
                )
                if encoded_ok:
                    last_sent = now
                    threading.Thread(
                        target=send_motion_event,
                        args=(client, args.server_url, camera_id, args.webhook_secret,
                              encoded.tobytes()),
                        daemon=True,
                    ).start()
                else:
                    print("[motion-watcher] JPEG encode failed, skipping frame.", file=sys.stderr)
    except KeyboardInterrupt:
        print("\n[motion-watcher] stopping.")
    finally:
        cap.release()
        client.close()
        if args.preview:
            try:
                cv2.destroyAllWindows()
            except cv2.error:
                pass


if __name__ == "__main__":
    main()
