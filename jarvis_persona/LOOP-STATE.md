# Automated Camera & Face Recognition Pipeline — Loop State

## Discovery note

Before starting work, an audit found the server-side pipeline already existed in full,
shipped in commit `b86634a` ("feat(vision): add opt-in motion greeting and face
enrollment engine"), with test coverage and default-off consent gating. Rather than
re-implement it, this loop targeted the one genuinely missing piece: the camera-side
motion sensor that feeds the existing webhook.

## Completed Steps

### Step 1 — Server-side pipeline (pre-existing, verified not re-implemented)
- **Status:** `[COMPLETED]` (prior to this loop)
- **What exists:** `POST /vision/motion` webhook (cooldown, HMAC-style secret check,
  fire-and-forget virtual-thread processing) → `MotionEventService` (image extraction,
  cooldown, face match) → `CompreFaceClient` (real HTTP face recognition) →
  `PendingVisitorStore` (TTL-pruned pending visitors, deletes snapshot files on
  expiry) → `PresenceGreetingService` (auto "what's your name" prompt on unknown) →
  `POST /vision/enroll` (`UnknownVisitorEnrollmentService` completes name → face
  enrollment). `GET /vision/status` and `GET /vision/visits` for observability.
  Both `vision.motion.enabled` and `vision.face.enabled` default to `false`
  (`VisionSettings.java`) — fully inert until explicitly opted into.
- **Files:** `java/app/src/main/java/com/jarvis/app/{WebServer,MotionEventService,
  CompreFaceClient,FaceRecognitionClient,PendingVisitorStore,
  UnknownVisitorEnrollmentService,PresenceGreetingService,VisionSettings}.java`

### Step 2 — Camera-side motion detector (new, this loop)
- **Status:** `[COMPLETED]`
- **Changes made:** Added `examples/motion_watcher/motion_watcher.py` — a standalone
  Python script that opens a webcam/RTSP source via OpenCV, does grayscale frame
  differencing with a slowly-adapting reference frame (contour-area threshold), and
  on a crossed threshold JPEG-encodes the triggering frame and POSTs it to the
  existing `/vision/motion` webhook (`{cameraId, timestamp, imageBase64,
  snapshotUrl}`, optional `X-Vision-Webhook-Secret` header) on a background thread so
  a slow/unreachable server never stalls frame capture. Client-side cooldown is
  independent of the server's own per-camera cooldown. Added
  `examples/motion_watcher/README.md` documenting setup/usage/privacy behavior.
  Added `vision-motion = ["opencv-python>=4.9"]` to `pyproject.toml`
  optional-dependencies (opt-in install, matches existing extras pattern; base
  `httpx` dependency already covers the HTTP client need).
- **Verification:** `python -m py_compile` clean; `pyproject.toml` re-parsed with
  `tomllib` after edit to confirm no TOML breakage. No ruff available in this
  environment to lint against repo style rules — flagged as unverified.
- **Files:** `examples/motion_watcher/motion_watcher.py` (new),
  `examples/motion_watcher/README.md` (new), `pyproject.toml` (1-line addition)

## Next Sub-task

None identified as required by the original task goal — motion capture, the
server handshake, auto-save/log, and the "solicit name" dialogue trigger are all
now in place end-to-end. Suggested follow-ups if wanted (not started, not assumed
approved):
- Run `ruff check` / `pytest` on the new script once a dev environment with those
  tools is available (this session had neither).
- Manual end-to-end smoke test: start the Java server with
  `JARVIS_VISION_MOTION_ENABLED=true`, run `motion_watcher.py` against a real
  webcam, confirm a pending-visitor snapshot lands under `~/.jarvis/vision` and
  `/vision/visits` reflects the event.
- `.env.example` currently documents no `JARVIS_VISION_*` / `JARVIS_FACE_*` vars
  even though `VisionSettings.java` reads them — worth adding for discoverability,
  but out of scope for this task and not touched.

## Goal Status: COMPLETE

All parts of the stated goal (motion detection, `/api/recognize`-equivalent server
handshake, unknown-visitor auto-save/log/solicit-name workflow) are implemented and
wired together. Ending the loop here per termination criteria rather than inventing
further unrequested work.
