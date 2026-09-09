# UI Repo Audit — JARVIS UI Source-of-Truth Extraction

**Date:** 2026-08-19
**Workspace:** `%TEMP%\claude\C--Users-BillAsmar\e80a6420-...\scratchpad\ui_extract\`
**Target:** `C:\dev\JARVIS_2.0\God Mode Agent\apps\web` (Next.js 14.2.35, React 18, plain Tailwind-style classes, no component library)

Ranking summary (weighted: control-center cohesion / interaction depth / extensibility / production-readiness, each 0–10):

| Rank | Repo | Cohesion | Depth | Extensibility | Prod-ready | Overall confidence |
|------|------|----------|-------|---------------|------------|--------------------|
| 1 | FatihMakes/Mark-L | 9 | 9 | 7 | 7 | **8/10** |
| 2 | FatihMakes/Mark-XXXIX-OR | 7 | 6 | 7 | 5 | **6/10** |
| 3 | AnubhavChaturvedi-GitHub/jarvis-ai-assistant | 2 | 3 | 3 | 1 | **3/10** |

---

## 1. FatihMakes/Mark-L — RANK 1

- **Ref:** `b4d6ae9bc7c2c4a60c19361efb1c6c558d129962` (default branch `main`)
- **Stack:** Python 3, PyQt6 desktop HUD (`ui.py`, 3,338 lines) **plus** a FastAPI web dashboard (`dashboard/server.py`, 794 lines) serving a self-contained vanilla-JS chat surface (`dashboard/static/app.html`, 559 lines). Gemini Live native-audio backend. No node toolchain — the web UI is dependency-free HTML/CSS/JS with a locally vendored CryptoJS.

### Strengths
- **True "single control center" UX** — one `MainWindow` composes: header, left system-metrics panel, center animated HUD canvas with a `QSplitter` content panel, live camera-feed container that swaps in for the HUD, clipboard panel, and log feed. This is exactly the JARVIS control-center archetype.
- **Deepest interaction surface of the three repos** — every element is actionable: file drop zone with per-file progress, mic button streaming real PCM16 audio over WebSocket to Gemini Live, wake button, remote-session QR pairing, accent hue wheel that re-themes every live widget, interrupt ("stop mid-speech") callback.
- **Production touches none of the others have:** bearer-token auth + device-login/QR pairing + device revocation, AES-256-CBC encrypted command payloads, TLS certs support, `CREATE_NO_WINDOW` subprocess hardening, packaged installer (`core/installer.py`).
- **Clean WS protocol** worth adopting verbatim: message types `log`, `status`, `wake`, `sys`, `file_received`; routes `/login`, `/api/device-login`, `/api/revoke-devices`, `/api/command`, `/api/wake`, `/api/upload`, `/api/files`, `/uploads/{filename}`, `/ws`, `/ws/phone-audio`.

### Weaknesses
- Desktop HUD is PyQt6 — visual patterns port to web only as a rewrite (canvas/SVG), not a copy.
- `ui.py` is a 3,338-line monolith (violates our <800-line file rule); classes must be split on port.
- Turkish comments scattered through `ui.py`; dashboard HTML is a single file with inline CSS/JS.
- Auth is homegrown (sessionStorage token + AES salt constant in client JS) — fine as an interaction pattern, not as a security boundary to copy.

### Exact UI folders/files (one-line purpose)
| Path | Purpose |
|------|---------|
| `ui.py` (class `C`, `apply_ui_accent`, `retheme_all_widgets`, lines 62–170) | Design-token palette (deep-navy bg, cyan primary, amber/green/red status) + live hue-shift retheming engine |
| `ui.py` (class `_SysMetrics`, 217–339) | CPU/RAM/GPU/net sampling incl. NVML-via-DLL GPU readout with no subprocess |
| `ui.py` (class `HudCanvas`, 340–598) | Animated arc-reactor HUD centerpiece (rings, particles, state-driven glow) |
| `ui.py` (class `MetricBar`, 599–652) | Compact labeled metric bar used in left system panel |
| `ui.py` (class `LogWidget`, 653–763) | Color-coded scrolling event/conversation log |
| `ui.py` (classes `FileDropZone` + `_DropCanvas`, 774–955) | Drag-drop file intake with animated drop canvas |
| `ui.py` (class `_CameraPreview`, 956–1022) | Small live camera frame overlay |
| `ui.py` (class `SetupOverlay`, 1023–1151) | First-run API-key/config overlay |
| `ui.py` (classes `HueWheel` + `CustomizeOverlay`, 1152–1414) | Accent color picker + assistant-name customization |
| `ui.py` (class `ClipboardPanel`, 1415–1500) | Clipboard history side panel |
| `ui.py` (class `RemoteKeyOverlay`, 1501–1728) | Remote-session key/QR pairing overlay |
| `ui.py` (class `MainWindow`, 1729–3241) | Control-center composition: header / left metrics rail / HUD+content splitter / camera swap / callbacks |
| `dashboard/server.py` | FastAPI remote server: token+QR auth, AES decrypt, WS log/status broadcast, uploads, phone-audio WS |
| `dashboard/static/app.html` | Full remote chat surface: status pill, message feed, file cards w/ progress, PCM16 mic streaming, toast |
| `dashboard/static/login.html` | Session-key login page feeding sessionStorage token |
| `main.py` (UI-wiring sections only) | Shows callback contract between UI and assistant core (`on_text_command`, `on_interrupt`, signals) |

### Portability risk notes
- PyQt6 → React: `HudCanvas` must be re-implemented on `<canvas>`; ~2–3 days, low algorithmic risk (paint math is self-contained).
- `_SysMetrics` needs a server-side equivalent (psutil already in God Mode API stack) — metrics move behind an API route.
- app.html JS is framework-free and splits cleanly into React hooks (`useVoiceStream`, `useFileUpload`, `useJarvisSocket`); highest-fidelity, lowest-risk port in the whole audit.
- Do **not** copy `config/certs/jarvis.crt|key` (committed private key — provenance red flag; generate our own).

---

## 2. FatihMakes/Mark-XXXIX-OR — RANK 2

- **Ref:** `eac6378a2411caaca6b382ecc5c04b3c492b5851` (default branch `main`)
- **Stack:** Python 3, PyQt6 HUD (`ui.py`, 1,534 lines) — an earlier/leaner cut of the same HUD lineage as Mark-L (same `C` tokens, `HudCanvas`, `MetricBar`, `LogWidget`, `FileDropZone`, `SetupOverlay`, `MainWindow`). OpenRouter LLM client. **No web dashboard.**

### Strengths
- Adds what Mark-L lacks: an **agent orchestration layer** (`agent/planner.py` 277 ln, `agent/executor.py` 399 ln, `agent/task_queue.py` 220 ln, `agent/error_handler.py` 196 ln) — plan → queue → execute → error-recover lifecycle with inspectable task states. This is the interaction model our governance rail (Approvals/Audit/Planner panels) should visualize.
- Leaner `ui.py` is easier to read when porting shared classes (identical class names to Mark-L).

### Weaknesses
- UI is a strict subset of Mark-L (no camera preview, no theming, no clipboard, no remote overlay, no dashboard) — extracting its UI duplicates Mark-L at lower fidelity.
- No auth, no remote access, no encryption. Prototype-grade.

### Exact UI folders/files
| Path | Purpose |
|------|---------|
| `agent/planner.py` | LLM plan generation → structured task list (source model for Planner panel) |
| `agent/task_queue.py` | Task states/queue semantics (source model for governance queue visualization) |
| `agent/executor.py` | Step execution + progress reporting contract |
| `agent/error_handler.py` | Retry/recovery states worth surfacing in the audit rail |
| `ui.py` (class `SetupOverlay`, 858–1011) | Simpler first-run overlay — reference when Mark-L's is over-featured |
| `ui.py` (class `MainWindow`, 1012–1481) | Minimal control-center layout — reference layout skeleton |

### Portability risk notes
- Extract the **agent layer's state machine as a UI contract**, not the code: map its task states to TypeScript types for the governance rail. Low risk; pure reference.
- Skip its UI classes wherever Mark-L has the same class — Mark-L's are supersets.

---

## 3. AnubhavChaturvedi-GitHub/jarvis-ai-assistant — RANK 3

- **Ref:** `28d64ec0dad252f7c5aaad468f2ee820a684ace5` (default branch `main`)
- **Stack:** Python 3, PyQt5. The entire UI (`ui.py`, 117 lines) is a frameless fullscreen window playing a mic GIF that scales when the assistant prints output, and spawns `main.py` via a **hardcoded absolute path to the author's desktop** (`C:\Users\chatu\OneDrive\Desktop\...`).

### Strengths
- Broad interaction-model catalog: wake/STT (`NetHyTechSTT/listen.py`), fast TTS (`TextToSpeech/Fast_DF_TTS.py`), vision brains (`Vision/Vbrain.py`, `Vision/MVbrain.py`), realtime web answers (`Real_Time/`), device features (`Features/` — volume, brightness, clap detection), and automation modules. Useful as a checklist of assistant capabilities a control center should expose.

### Weaknesses
- Effectively **no UI to extract**: one GIF label, no layout, no navigation, no chat surface, no settings.
- Hardcoded user paths, `chromedriver.exe` and chat-history text files committed, no packaging, no auth. Production-readiness ≈ 1/10.

### Exact UI folders/files
| Path | Purpose |
|------|---------|
| `ui.py` | Only UI artifact: GIF-reactive listening indicator (pattern: visual state tied to assistant output activity) |
| `Vision/Vbrain.py`, `Vision/MVbrain.py` | Camera/vision interaction model → informs Vision panel affordances |
| `NetHyTechSTT/listen.py`, `TextToSpeech/Fast_DF_TTS.py` | Voice loop interaction model → informs Voice panel states (listening/speaking) |
| `Features/set_get_volume.py`, `Features/set_br.py`, `Features/br_persentage.py` | Device-control actions → informs Integrations/Tools panel action catalog |

### Portability risk notes
- **Adaptation path (no web UI exists):** do not port code. Extract the *state vocabulary* — idle / listening / processing / speaking, plus "output activity pulses the visual" — and encode it as the animation contract for the ported `HudCanvas`. Everything else in this repo is backend behavior our God Mode API already covers.
- Binary/junk exclusions mandatory: `chromedriver.exe`, `*.gif`, `captured_image.png`, `chat_hystory.txt`, `history.txt`, `input.txt`, `log.txt`.
