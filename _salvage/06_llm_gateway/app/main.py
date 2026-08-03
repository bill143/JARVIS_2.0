"""
ECHO entrypoint. FastAPI app that:
  1. Mounts LiteLLM's proxy router at /v1/*
  2. Mounts /health endpoint
  3. Loads Langfuse credentials at startup
  4. Enforces ECHO_MASTER_KEY on all /v1/* requests
"""
from __future__ import annotations

import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from litellm.proxy.proxy_server import app as litellm_app, initialize

from app.health import router as health_router

CONFIG_PATH = Path(__file__).resolve().parent.parent / "litellm_config.yaml"


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Initialize LiteLLM's proxy from our yaml BEFORE accepting requests
    await initialize(config=str(CONFIG_PATH))
    yield


app = FastAPI(
    title="ECHO LiteLLM Proxy",
    version="0.1.0",
    description="Multi-provider routing proxy for the JARVIS stack",
    lifespan=lifespan,
)

# Health endpoint FIRST so it doesn't require master key
app.include_router(health_router)

# Mount LiteLLM's full proxy (handles /v1/chat/completions, /v1/models, etc.)
# LiteLLM internally enforces the master_key from general_settings
app.mount("/", litellm_app)