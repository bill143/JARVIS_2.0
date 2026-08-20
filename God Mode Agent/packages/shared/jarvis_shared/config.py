"""Central settings loaded from environment / .env (Windows-safe, pathlib everywhere)."""

from __future__ import annotations

import secrets as _secrets
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# Ephemeral dev secrets used only when JWT secrets are not configured.
_DEV_SECRETS: dict[str, str] = {}


def _dev_secret(kind: str) -> str:
    return _DEV_SECRETS.setdefault(kind, _secrets.token_hex(32))


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    openai_api_key: str = ""
    anthropic_api_key: str = ""
    deepgram_api_key: str = ""
    elevenlabs_api_key: str = ""

    # NVIDIA NIM (OpenAI-compatible endpoint)
    nvidia_api_key: str = ""
    nvidia_base_url: str = "https://integrate.api.nvidia.com/v1"
    nvidia_model: str = "nvidia/nemotron-3-super-120b-a12b"

    database_url: str = "sqlite:///./jarvis.db"
    chroma_persist_dir: str = "./data/chroma"

    default_model_provider: str = "openai"
    default_model_name: str = "gpt-4o"
    enable_fallbacks: bool = True

    safe_workspace_dir: str = "./workspace"
    max_tool_iterations: int = 6

    api_host: str = "127.0.0.1"
    api_port: int = 8000
    web_port: int = 3000
    desktop_runtime_port: int = 8010

    tesseract_cmd: str = ""
    backend_public_url: str = "http://127.0.0.1:8000"

    cors_origins: str = "*"
    rate_limit_rpm: int = 240
    frame_sample_rate_hz: float = 1.0
    sandbox_timeout_seconds: float = 8.0

    # --- Phase 2: identity & auth ---
    jwt_secret: str = ""
    jwt_refresh_secret: str = ""
    jwt_access_ttl_min: int = 15
    jwt_refresh_ttl_days: int = 14
    auth_mode: str = "local"
    allow_dev_auth_bypass: bool = False
    auth_max_failed_attempts: int = 5
    auth_lockout_minutes: int = 15
    jarvis_api_key: str = ""

    # --- Phase 2: reliability / queue ---
    redis_url: str = "redis://127.0.0.1:6379/0"
    queue_backend: str = "redis"
    queue_autostart: bool = True
    queue_poll_interval: float = 0.5
    queue_retry_base: float = 2.0
    queue_max_attempts: int = 3
    idempotency_ttl_hours: int = 24

    # --- Phase 2: API/WS security ---
    rate_limit_per_min: int = 60
    cors_allowed_origins: str = "http://127.0.0.1:3000"
    max_request_body_mb: float = 5.0
    ws_heartbeat_sec: int = 20
    ws_max_connections_per_ip: int = 20

    # --- Phase 2: policy & safety ---
    policy_default_action: str = "deny"
    policy_domain_allowlist: str = "example.com"
    policy_file_write_extensions: str = ".txt,.md,.json,.csv,.log"
    injection_block_threshold: float = 0.7
    enable_audit_log_hash_chain: bool = True

    # --- Phase 2: observability ---
    otel_enabled: bool = True
    otel_exporter: str = "console"
    otel_endpoint: str = ""
    prometheus_enabled: bool = True
    log_level: str = "INFO"

    # --- Phase 3: planner / agents ---
    agent_mode: str = "plan-and-execute"
    max_agent_rounds: int = 6
    planner_max_steps: int = 20
    workflow_checkpoint_interval_sec: int = 15

    # --- Phase 3: RAG / retrieval ---
    rag_hybrid_enabled: bool = True
    rag_bm25_enabled: bool = True
    reranker_provider: str = ""
    reranker_model: str = ""
    rag_top_k: int = 12
    rag_rerank_top_n: int = 6
    citation_required: bool = True

    # --- Phase 3: memory governance ---
    memory_default_ttl_days: int = 90
    memory_confidence_min: float = 0.55
    memory_pin_limit_per_user: int = 200

    # --- Phase 3: cost / routing ---
    routing_budget_daily_usd: float = 25.0
    routing_latency_target_ms: int = 2500
    cache_enabled: bool = True
    cache_ttl_sec: int = 1800

    # --- Phase 3: compliance / governance ---
    compliance_mode: bool = False
    audit_strict_mode: bool = False
    data_retention_days: int = 180
    evidence_export_dir: str = "./exports/evidence"

    # --- ECHO Command Stage 3: activity log / system health ---
    activity_log_db: str = "./data/activity-log.db"
    tailscale_exe: str = ""

    # --- ECHO Command Stage 2: Kokoro TTS ---
    kokoro_tts_enabled: bool = True
    kokoro_tts_url: str = "http://127.0.0.1:8767"
    kokoro_default_voice: str = "bm_george"
    # CPU synthesis of long replies can exceed 30s; fall back only past 120s.
    kokoro_timeout_seconds: float = 120.0
    voices_config_path: str = ""

    # --- Phase 3: evals / quality gates ---
    evals_required_for_release: bool = True
    eval_pass_threshold: float = 0.85
    regression_tolerance: float = 0.03

    @property
    def evidence_export_path(self) -> Path:
        p = Path(self.evidence_export_dir).resolve()
        p.mkdir(parents=True, exist_ok=True)
        return p

    @property
    def approved_providers(self) -> set[str]:
        # Providers permitted for sensitive tasks in compliance mode.
        return {"anthropic", "openai", "mock"}

    @property
    def activity_log_path(self) -> Path:
        return Path(self.activity_log_db).resolve()

    @property
    def sqlite_path(self) -> Path:
        url = self.database_url
        if url.startswith("sqlite:///"):
            return Path(url[len("sqlite:///"):]).resolve()
        return Path("jarvis.db").resolve()

    @property
    def workspace_path(self) -> Path:
        p = Path(self.safe_workspace_dir).resolve()
        p.mkdir(parents=True, exist_ok=True)
        return p

    @property
    def chroma_path(self) -> Path:
        p = Path(self.chroma_persist_dir).resolve()
        p.mkdir(parents=True, exist_ok=True)
        return p

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]

    @property
    def cors_allowed_list(self) -> list[str]:
        explicit = [o.strip() for o in self.cors_allowed_origins.split(",") if o.strip()]
        return explicit or self.cors_origin_list or ["*"]

    @property
    def effective_jwt_secret(self) -> str:
        return self.jwt_secret or _dev_secret("access")

    @property
    def effective_jwt_refresh_secret(self) -> str:
        return self.jwt_refresh_secret or _dev_secret("refresh")

    @property
    def domain_allowlist(self) -> list[str]:
        return [d.strip().lower() for d in self.policy_domain_allowlist.split(",") if d.strip()]

    @property
    def file_write_extension_set(self) -> set[str]:
        return {e.strip().lower() for e in self.policy_file_write_extensions.split(",") if e.strip()}


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
