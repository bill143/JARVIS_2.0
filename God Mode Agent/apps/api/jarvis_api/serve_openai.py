"""Alternate ASGI entrypoint: the full God Mode app + the OpenAI-compatible shim.

Run this target instead of ``jarvis_api.main:app`` to expose /v1/models and
/v1/chat/completions alongside every native endpoint::

    uvicorn jarvis_api.serve_openai:app --host 127.0.0.1 --port 8000

Reversible: run ``jarvis_api.main:app`` for the original app with no shim.
"""

from __future__ import annotations

from jarvis_api.main import app
from jarvis_api.openai_compat import register_openai_compat

register_openai_compat(app)

__all__ = ["app"]
