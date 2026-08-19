"""Alternate ASGI entrypoint: the full God Mode app + additive extension routes.

Serves the native app plus:
- the OpenAI-compatible shim (/v1/models, /v1/chat/completions),
- the compliance management console routes (/compliance/*), and
- the Evals v2 platform routes (/evals/v2/*).

Run this target instead of ``jarvis_api.main:app``::

    uvicorn jarvis_api.serve_openai:app --host 127.0.0.1 --port 8000

Reversible: run ``jarvis_api.main:app`` for the original app with no extensions.
"""

from __future__ import annotations

from jarvis_api.compliance_ext_routes import register_compliance_ext
from jarvis_api.evals_v2_routes import register_evals_v2
from jarvis_api.main import app
from jarvis_api.openai_compat import register_openai_compat

register_openai_compat(app)
register_compliance_ext(app)
register_evals_v2(app)

__all__ = ["app"]
