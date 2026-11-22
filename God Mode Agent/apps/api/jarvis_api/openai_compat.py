"""OpenAI-compatible shim for the JARVIS God Mode Agent.

Exposes a minimal slice of the OpenAI REST surface:

    GET  /v1/models
    POST /v1/chat/completions   (streaming + non-streaming)

mapped onto the agent's native /chat flow (jarvis_core.AgentLoop). This lets any
OpenAI-compatible client -- in particular the Java JARVIS "Models & Providers"
brain, which speaks the OpenAI wire format -- use God Mode as its chat backend
with only a base-URL (and API-key) change and no Java refactor.

Wiring is additive and reversible: routes are attached by register_openai_compat().
Nothing in the core app (main.py) is modified.
"""

from __future__ import annotations

import json
import os
import time
import uuid

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

from jarvis_api.deps import PrincipalBoundTools, resolve_principal
from jarvis_shared.errors import AuthRequired, PromptInjectionBlocked

MODEL_ID = "god-mode-agent"
MAX_PROMPT_CHARS = 32000  # matches the native ChatRequest.message cap


def _flatten_messages(messages: list[dict]) -> str:
    """Collapse an OpenAI ``messages`` array into a single prompt string.

    System messages become a leading context block; the remaining turns are
    labelled so the agent receives the full conversation in one stateless shot
    (OpenAI clients resend history on every call, so we do not rely on
    server-side session state here).
    """
    system_parts = [
        str(m.get("content", "")).strip()
        for m in messages
        if m.get("role") == "system" and str(m.get("content", "")).strip()
    ]
    turns = [
        m for m in messages
        if m.get("role") != "system" and str(m.get("content", "")).strip()
    ]

    if len(turns) == 1 and turns[0].get("role") == "user":
        body = str(turns[0].get("content", "")).strip()
    else:
        labels = {"user": "User", "assistant": "Assistant", "tool": "Tool"}
        body = "\n".join(
            f"{labels.get(m.get('role'), str(m.get('role')).capitalize())}: "
            f"{str(m.get('content', '')).strip()}"
            for m in turns
        )

    prefix = ("\n".join(system_parts) + "\n\n") if system_parts else ""
    prompt = (prefix + body).strip() or "(empty message)"
    return prompt[:MAX_PROMPT_CHARS]


def _resolve_principal_lenient(request: Request):
    """Auth resolution tolerant of how OpenAI clients present credentials.

    Order:
      1. Native resolution (valid Bearer JWT, or X-API-Key, or dev bypass).
      2. If an ``Authorization: Bearer <token>`` was sent but is not a JWT, try
         it as a God Mode API key (``jk_...``) -- OpenAI clients place the API key
         in the Bearer slot, so a minted key pasted into the Java "API key" field
         works.
      3. If ``V1_ALLOW_ANON`` is truthy in the environment, fall back to the dev
         principal (local integration only -- clearly opt-in).
    """
    jarvis = request.app.state.jarvis
    auth = jarvis.auth

    try:
        return resolve_principal(request)
    except Exception:
        pass

    header = request.headers.get("authorization", "")
    if header.lower().startswith("bearer "):
        token = header[7:].strip()
        try:
            return auth.principal_from_api_key(token)
        except Exception:
            pass

    if os.getenv("V1_ALLOW_ANON", "").strip().lower() in ("1", "true", "yes", "on"):
        return auth.dev_bypass_principal()

    raise AuthRequired(
        "authentication required: send the God Mode API key as a Bearer token, or "
        "set ALLOW_DEV_AUTH_BYPASS=true / V1_ALLOW_ANON=true for local testing"
    )


async def _run_agent(request: Request, prompt: str):
    jarvis = request.app.state.jarvis
    principal = _resolve_principal_lenient(request)
    session_id = f"{principal.tenant}:v1-{uuid.uuid4().hex[:12]}"
    jarvis.metadata.touch_session(session_id, principal.user_id)
    agent = jarvis.base_agent()
    agent.registry = PrincipalBoundTools(jarvis.governed, principal)
    return await agent.run(prompt, session_id=session_id, user_id=principal.user_id)


def register_openai_compat(app: FastAPI) -> None:
    """Attach the /v1/* OpenAI-compatible routes to an existing app. Idempotent."""
    if getattr(app.state, "_openai_compat_registered", False):
        return
    app.state._openai_compat_registered = True

    @app.get("/v1/models")
    async def list_models():
        created = int(time.time())
        return {
            "object": "list",
            "data": [
                {
                    "id": MODEL_ID,
                    "object": "model",
                    "created": created,
                    "owned_by": "jarvis-god-mode",
                },
            ],
        }

    @app.post("/v1/chat/completions")
    async def chat_completions(body: dict, request: Request):
        messages = body.get("messages")
        if not isinstance(messages, list) or not messages:
            return JSONResponse(
                status_code=400,
                content={
                    "error": {
                        "message": "'messages' must be a non-empty array",
                        "type": "invalid_request_error",
                        "param": "messages",
                        "code": None,
                    }
                },
            )
        prompt = _flatten_messages(messages)
        stream = bool(body.get("stream", False))
        requested_model = str(body.get("model") or MODEL_ID)

        try:
            result = await _run_agent(request, prompt)
        except PromptInjectionBlocked as exc:
            return JSONResponse(
                status_code=400,
                content={
                    "error": {
                        "message": exc.message,
                        "type": "prompt_injection_blocked",
                        "code": "prompt_injection",
                    }
                },
            )

        reply = result.reply or ""
        model_name = getattr(result, "model", None) or requested_model
        completion_id = "chatcmpl-" + uuid.uuid4().hex[:24]
        created = int(time.time())
        prompt_tokens = max(len(prompt) // 4, 1)
        completion_tokens = max(len(reply) // 4, 1)

        if not stream:
            return JSONResponse(
                content={
                    "id": completion_id,
                    "object": "chat.completion",
                    "created": created,
                    "model": model_name,
                    "choices": [
                        {
                            "index": 0,
                            "message": {"role": "assistant", "content": reply},
                            "finish_reason": "stop",
                        }
                    ],
                    "usage": {
                        "prompt_tokens": prompt_tokens,
                        "completion_tokens": completion_tokens,
                        "total_tokens": prompt_tokens + completion_tokens,
                    },
                }
            )

        def _sse():
            def frame(delta: dict, finish=None):
                payload = {
                    "id": completion_id,
                    "object": "chat.completion.chunk",
                    "created": created,
                    "model": model_name,
                    "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
                }
                return f"data: {json.dumps(payload)}\n\n"

            yield frame({"role": "assistant"})
            words = reply.split(" ") or [""]
            for i in range(0, len(words), 6):
                piece = " ".join(words[i:i + 6])
                if i + 6 < len(words):
                    piece += " "
                yield frame({"content": piece})
            yield frame({}, finish="stop")
            yield "data: [DONE]\n\n"

        return StreamingResponse(_sse(), media_type="text/event-stream")
