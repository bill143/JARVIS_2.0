"""Structured JSON logging: correlation IDs, secret masking, telemetry spans."""

from __future__ import annotations

import contextvars
import json
import logging
import os
import sys
import time
from contextlib import contextmanager
from datetime import UTC, datetime

from jarvis_shared.redaction import mask_secrets

# Correlation context propagated into every log line when set.
request_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("request_id", default="")
session_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("session_id", default="")
user_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("user_id", default="")
tool_call_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("tool_call_id", default="")

_CTX_VARS = {
    "request_id": request_id_var,
    "session_id": session_id_var,
    "user_id": user_id_var,
    "tool_call_id": tool_call_id_var,
}


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict = {
            "ts": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": mask_secrets(record.getMessage()),
        }
        for name, var in _CTX_VARS.items():
            value = var.get()
            if value:
                payload.setdefault(name, value)
        extra = getattr(record, "extra_fields", None)
        if isinstance(extra, dict):
            for key, value in extra.items():
                payload[key] = mask_secrets(value) if isinstance(value, str) else value
        if record.exc_info and record.exc_info[0]:
            payload["exc_type"] = record.exc_info[0].__name__
        return json.dumps(payload, ensure_ascii=False, default=str)


def get_logger(name: str) -> logging.Logger:
    logger = logging.getLogger(name)
    if not any(isinstance(h, logging.StreamHandler) and isinstance(h.formatter, JsonFormatter) for h in logger.handlers):
        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(JsonFormatter())
        logger.addHandler(handler)
        logger.propagate = False
    level_name = os.environ.get("LOG_LEVEL", "INFO").upper()
    logger.setLevel(getattr(logging, level_name, logging.INFO))
    return logger


def log_event(logger: logging.Logger, message: str, **fields) -> None:
    logger.info(message, extra={"extra_fields": fields})


@contextmanager
def span(logger: logging.Logger, name: str, **fields):
    """Telemetry span: logs start/end with duration_ms."""
    start = time.perf_counter()
    try:
        yield
        status = "ok"
    except Exception:
        status = "error"
        raise
    finally:
        duration_ms = round((time.perf_counter() - start) * 1000, 2)
        log_event(logger, f"span:{name}", span=name, status=status, duration_ms=duration_ms, **fields)
