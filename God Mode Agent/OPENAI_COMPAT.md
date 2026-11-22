# OpenAI-compatible shim (Java JARVIS <-> God Mode Agent)

This lets the Java JARVIS **Models & Providers** brain (which speaks the OpenAI
wire format) use the God Mode Agent as its chat backend with **only a base-URL +
API-key change and no Java refactor**.

The layer is **additive and reversible** -- `apps/api/jarvis_api/main.py` is not
modified. The shim is attached by an alternate ASGI entrypoint
(`jarvis_api.serve_openai:app`).

## Endpoints added

| Method | Path | Purpose |
|---|---|---|
| GET  | `/v1/models` | Lists one model, `god-mode-agent`, so the Java live-model dropdown populates. |
| POST | `/v1/chat/completions` | OpenAI ChatCompletion (streaming + non-streaming), mapped onto the native `/chat` AgentLoop. |

All native endpoints (`/chat`, `/health`, `/metrics`, `/auth/*`, `/workflows`,
`/agents/run`, `/rag/*`, ...) remain available unchanged.

## Run the API with the shim

```powershell
cd "C:\dev\JARVIS_2.0\God Mode Agent"
.\scripts\dev-api-openai.ps1        # serves jarvis_api.serve_openai:app on API_HOST:API_PORT (default 127.0.0.1:8000)
```

(Original, shim-free: `.\scripts\dev-api.ps1`.)

## Smoke test

```powershell
.\scripts\smoke-openai-compat.ps1                       # logs in as admin, hits /v1/models + /v1/chat/completions
.\scripts\smoke-openai-compat.ps1 -ApiKey jk_your_key   # or use a minted API key
```

Offline pytest (mock provider, no keys needed):

```powershell
.\.venv\Scripts\python -m pytest tests/integration/test_openai_compat.py -v
```

## Point the Java JARVIS brain at it

In the JARVIS **Settings -> Models & Providers** pane (keys stay local,
restart-applied):

| Field | Value |
|---|---|
| Base URL | `http://127.0.0.1:8000/v1` |
| API key | a God Mode API key (`POST /apikeys` as admin -> `jk_...`), sent by Java as `Authorization: Bearer`. For local-only testing you may instead set `V1_ALLOW_ANON=true` (or `ALLOW_DEV_AUTH_BYPASS=true`) in `.env` and use any placeholder key. |
| Model | `god-mode-agent` (auto-listed via `/v1/models`) |

Then restart/apply in JARVIS and send a chat message from the UI -- the response
now comes from the God Mode Agent.

## Auth behavior

The shim resolves the caller in this order: valid Bearer JWT -> `X-API-Key` ->
Bearer value tried as a God Mode API key -> (`V1_ALLOW_ANON`/dev bypass) dev
principal. This tolerates OpenAI clients that always send the API key in the
`Authorization: Bearer` slot.

## Rollback

Fully additive; to remove:

1. Point JARVIS back at its previous provider base URL (or run `.\scripts\dev-api.ps1`).
2. Delete: `apps/api/jarvis_api/openai_compat.py`, `apps/api/jarvis_api/serve_openai.py`,
   `scripts/dev-api-openai.ps1`, `scripts/smoke-openai-compat.ps1`,
   `tests/integration/test_openai_compat.py`, and this file.

No core files were changed, so nothing else needs reverting.
