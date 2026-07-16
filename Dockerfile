# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — builder: compile the native Rust module and install the app into
# an isolated virtualenv. This stage carries the Rust + build toolchain; none
# of it ends up in the final image.
# ---------------------------------------------------------------------------
FROM python:3.12-slim AS builder

ENV DEBIAN_FRONTEND=noninteractive \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    VIRTUAL_ENV=/opt/venv \
    CARGO_HOME=/root/.cargo \
    PATH="/opt/venv/bin:/root/.cargo/bin:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        curl \
        git \
        pkg-config \
        libssl-dev \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Rust toolchain (minimal profile) for the maturin/pyo3 native extension.
# Download then execute (not `curl | sh`) so a failed download aborts the build
# loudly instead of silently skipping the toolchain install.
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs -o /tmp/rustup-init.sh \
    && sh /tmp/rustup-init.sh -y --profile minimal --default-toolchain stable \
    && rm /tmp/rustup-init.sh \
    && rustc --version && cargo --version

# uv (fast, reproducible Python dependency resolver).
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

WORKDIR /app

# Resolve dependencies first (better layer caching) then bring in the source.
COPY pyproject.toml uv.lock ./
COPY . .

# Install the app + server extra into /opt/venv, then build the native module.
RUN uv sync --frozen --extra server --no-dev \
    && uv pip install --python /opt/venv "maturin>=1.12.6" \
    && maturin develop --release \
        --manifest-path rust/crates/openjarvis-python/Cargo.toml

# ---------------------------------------------------------------------------
# Stage 2 — runtime: slim image, non-root, only the venv + source + git.
# ---------------------------------------------------------------------------
FROM python:3.12-slim AS runtime

ENV DEBIAN_FRONTEND=noninteractive \
    PATH="/opt/venv/bin:${PATH}" \
    VIRTUAL_ENV=/opt/venv \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    HOME=/home/jarvis

# git + curl are runtime needs (vault git-sync + container healthcheck).
RUN apt-get update && apt-get install -y --no-install-recommends \
        git \
        curl \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system jarvis \
    && useradd --system --gid jarvis --create-home --home-dir /home/jarvis jarvis

WORKDIR /app

COPY --from=builder /opt/venv /opt/venv
COPY --from=builder /app /app

# Data dir (config, credentials.toml, sqlite stores) lives under the user's
# home so it can be mounted as a persistent volume.
RUN mkdir -p /home/jarvis/.openjarvis /vault \
    && chown -R jarvis:jarvis /app /home/jarvis /vault

USER jarvis

EXPOSE 8000

# The app is fronted by a reverse proxy; it binds to all interfaces inside the
# isolated container network only (no host port is published in compose).
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:8000/health || exit 1

CMD ["jarvis", "serve", "--host", "0.0.0.0", "--port", "8000"]
