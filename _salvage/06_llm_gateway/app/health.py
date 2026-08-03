"""
Health endpoint for ECHO.

Verifies:
  - Process is alive (obvious)
  - All 7 required env vars are present (without exposing values)
  - LiteLLM config is parseable

Does NOT:
  - Actually call provider APIs (too expensive for a healthcheck)
  - Expose any key material
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Literal

import yaml
from fastapi import APIRouter, status
from pydantic import BaseModel

router = APIRouter(tags=["health"])

REQUIRED_KEYS = [
    "ECHO_MASTER_KEY",
    "ANTHROPIC_API_KEY",
    "OPENAI_API_KEY",
    "GOOGLE_API_KEY",
    "DEEPSEEK_API_KEY",
    "XAI_API_KEY",
]

OPTIONAL_KEYS = [
    "DATABASE_URL",
    "LANGFUSE_PUBLIC_KEY",
    "LANGFUSE_SECRET_KEY",
    "LANGFUSE_HOST",
]

CONFIG_PATH = Path(__file__).resolve().parent.parent / "litellm_config.yaml"


class KeyStatus(BaseModel):
    name: str
    present: bool
    # length only — NEVER the value
    length: int


class HealthResponse(BaseModel):
    status: Literal["ok", "degraded"]
    version: str = "0.1.0"
    required_keys: list[KeyStatus]
    optional_keys: list[KeyStatus]
    config_valid: bool
    model_count: int


@router.get("/health", response_model=HealthResponse, status_code=status.HTTP_200_OK)
async def health() -> HealthResponse:
    required = [
        KeyStatus(name=k, present=bool(os.getenv(k)), length=len(os.getenv(k, "")))
        for k in REQUIRED_KEYS
    ]
    optional = [
        KeyStatus(name=k, present=bool(os.getenv(k)), length=len(os.getenv(k, "")))
        for k in OPTIONAL_KEYS
    ]

    config_valid = False
    model_count = 0
    try:
        if CONFIG_PATH.is_file():
            data = yaml.safe_load(CONFIG_PATH.read_text(encoding="utf-8"))
            models = data.get("model_list", []) if isinstance(data, dict) else []
            model_count = len(models)
            config_valid = model_count > 0
    except yaml.YAMLError:
        config_valid = False

    all_required_present = all(k.present for k in required)
    overall: Literal["ok", "degraded"] = (
        "ok" if (all_required_present and config_valid) else "degraded"
    )

    return HealthResponse(
        status=overall,
        required_keys=required,
        optional_keys=optional,
        config_valid=config_valid,
        model_count=model_count,
    )