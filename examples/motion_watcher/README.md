# Motion Watcher

A camera-side operator that watches a webcam or video stream for motion using
pixel-delta frame differencing, and forwards the triggering frame to the Jarvis
server's face-recognition webhook.

This script intentionally does **no recognition, storage, or decision-making**
of its own -- it only answers "did something move?" cheaply and locally, then
hands the one relevant frame to the backend. Everything else (per-camera
cooldown, face matching, unknown-visitor snapshot persistence, the "what's your
name" prompt, retention/pruning) is owned by `MotionEventService` on the server,
reached through `POST /vision/motion`.

## Requirements

- OpenJarvis installed (`uv sync`)
- `opencv-python`, which is **not** installed by default:
  ```bash
  uv sync --extra vision-motion
  ```
- A Jarvis server reachable over HTTP, with `vision.motion.enabled`
  (`JARVIS_VISION_MOTION_ENABLED=true`) turned on. Until that's set, the server
  accepts every event but does nothing with it -- see
  `java/app/src/main/java/com/jarvis/app/VisionSettings.java`.
- Face recognition itself additionally requires `vision.face.enabled`
  (`JARVIS_FACE_ENABLED=true`) and a configured CompreFace instance. With motion
  enabled but face recognition off, events are still logged as `motion-only`.

## Usage

```bash
python examples/motion_watcher/motion_watcher.py --help

python examples/motion_watcher/motion_watcher.py \
    --server-url http://localhost:8080 \
    --camera-id front-door \
    --webhook-secret "$JARVIS_VISION_MOTION_WEBHOOK_SECRET"

# Non-webcam source, e.g. an RTSP camera:
python examples/motion_watcher/motion_watcher.py \
    --device "rtsp://192.168.1.50:554/stream1" --camera-id driveway
```

Set `--webhook-secret` to whatever the server has configured as
`vision.motion.webhookSecret` / `JARVIS_VISION_MOTION_WEBHOOK_SECRET`. If the
server has no secret configured, omit it -- but that's only appropriate for
local testing, since an unauthenticated webhook lets anyone on the network feed
it frames.

## How It Works

1. Grabs frames from the configured camera source (`cv2.VideoCapture`).
2. Converts each frame to blurred grayscale and diffs it against a slowly
   adapting reference frame (`cv2.absdiff` + threshold + dilate + contours).
3. If any contour's area clears `--min-area`, motion is declared.
4. The triggering color frame is JPEG-encoded, base64'd, and POSTed to
   `{server-url}/vision/motion` as `{cameraId, timestamp, imageBase64,
   snapshotUrl: ""}` on a background thread, so a slow or unreachable server
   never stalls frame capture.
5. A client-side cooldown (`--client-cooldown-sec`, separate from the server's
   own per-camera cooldown) keeps a sustained motion event from being
   re-encoded and sent on every single frame.
6. The reference frame is only updated while no motion is active, so a
   lingering subject doesn't get absorbed into the background and silently
   stop being detected.

Run with `--preview` to pop up a debug window showing the grayscale feed
(requires a display; safely ignored in headless environments).

## Privacy Note

This script starts continuously reading camera frames the moment it runs --
that's the opt-in. Frames only leave the machine when a pixel-delta threshold
is crossed; nothing is streamed or recorded continuously. What the server does
with a received frame is a separate, independently-gated decision (see
Requirements above).
