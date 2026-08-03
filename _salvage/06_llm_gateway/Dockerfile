# syntax=docker/dockerfile:1.7

# ============================================================
#  ECHO - LiteLLM Proxy Container
#  Base: python:3.13-slim-bookworm (Debian 12 slim)
#  Non-root runtime, multi-stage, pinned.
# ============================================================

# ---- Stage 1: builder ----
FROM python:3.13-slim-bookworm AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    UV_SYSTEM_PYTHON=1

# System build deps (removed from final image)
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential \
      curl \
      ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Install uv (fast Python package manager, same one you use locally)
RUN curl -LsSf https://astral.sh/uv/0.10.2/install.sh | sh
ENV PATH="/root/.local/bin:${PATH}"

WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

# ---- Stage 2: runtime ----
FROM python:3.13-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/app/.venv/bin:$PATH" \
    PORT=4000

# Non-root user
RUN groupadd --system --gid 1001 echo && \
    useradd --system --uid 1001 --gid echo --home /app --shell /usr/sbin/nologin echo

WORKDIR /app

# Copy venv from builder
COPY --from=builder --chown=echo:echo /app/.venv /app/.venv

# Copy application code
COPY --chown=echo:echo litellm_config.yaml ./
COPY --chown=echo:echo app ./app

USER echo

EXPOSE 4000

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -fsS http://localhost:${PORT}/health || exit 1

# Launch: uvicorn for FastAPI (which wraps LiteLLM + adds health/logging)
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "4000"]