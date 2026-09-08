"""Per-agent voice resolution from configs/voices.yaml (ECHO Command Stage 2)."""

from __future__ import annotations

import threading
from pathlib import Path

import yaml

from jarvis_shared.config import Settings
from jarvis_shared.logging import get_logger, log_event
from jarvis_shared.runtime_store import get_runtime_store

logger = get_logger("jarvis.voice.voices")

_lock = threading.Lock()
_cached: dict | None = None


def _candidate_paths(settings: Settings) -> list[Path]:
    candidates: list[Path] = []
    if settings.voices_config_path:
        candidates.append(Path(settings.voices_config_path))
    candidates.append(Path.cwd() / "configs" / "voices.yaml")
    # …/JARVIS_2.0/God Mode Agent/packages/voice/jarvis_voice/voices.py -> repo root is parents[4]
    candidates.append(Path(__file__).resolve().parents[4] / "configs" / "voices.yaml")
    return candidates


def _mtime(path: str) -> float:
    try:
        return Path(path).stat().st_mtime
    except OSError:
        return 0.0


def load_voice_map(settings: Settings) -> dict:
    """Load and cache the agent->voice map. Shape: {agents, default_agent, path, mtime}.

    The cache is invalidated when voices.yaml changes on disk, so editing the
    voice map takes effect on the next TTS call instead of requiring a restart.
    """
    global _cached
    with _lock:
        if _cached is not None:
            cached_path = _cached.get("path", "")
            # No path means the config was missing entirely; keep the empty map
            # rather than rescanning the filesystem on every TTS call.
            if not cached_path or _mtime(cached_path) == _cached.get("mtime"):
                return _cached
            log_event(logger, "voices.reloading", path=cached_path)
            _cached = None
        for path in _candidate_paths(settings):
            if not path.is_file():
                continue
            data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            agents = {str(k).upper(): str(v) for k, v in (data.get("agents") or {}).items()}
            if not agents:
                log_event(logger, "voices.empty_config", path=str(path))
                continue
            _cached = {
                "agents": agents,
                "default_agent": str(data.get("default_agent", "JARVIS")).upper(),
                "path": str(path),
                "mtime": _mtime(str(path)),
            }
            log_event(logger, "voices.loaded", path=str(path), agents=sorted(agents))
            return _cached
        log_event(logger, "voices.config_missing", tried=[str(p) for p in _candidate_paths(settings)])
        _cached = {"agents": {}, "default_agent": "JARVIS", "path": "", "mtime": 0.0}
        return _cached


def effective_voice_map(settings: Settings) -> dict:
    """YAML base map with persisted per-agent overrides layered on top (Run 2).

    Overrides live in the runtime store as `agent_voice.<AGENT>` and win over
    voices.yaml; they hot-swap per call and survive restarts.
    """
    base = load_voice_map(settings)
    agents = dict(base["agents"])
    overrides = {
        key.split(".", 1)[1].upper(): value
        for key, value in get_runtime_store(settings).all("agent_voice.").items()
        if value
    }
    agents.update({k: v for k, v in overrides.items() if k in agents})
    return {**base, "agents": agents, "overrides": overrides}


def known_agents(settings: Settings) -> set[str]:
    """Agent ids that have an explicit voice — used to validate a requested agent."""
    return set(load_voice_map(settings)["agents"])


def resolve_voice(agent_id: str | None, settings: Settings) -> str:
    """Map an agent id to its Kokoro voice; unknown agents use the default agent's voice."""
    voice_map = effective_voice_map(settings)
    agents: dict[str, str] = voice_map["agents"]
    key = (agent_id or voice_map["default_agent"]).upper()
    if key in agents:
        return agents[key]
    fallback = agents.get(voice_map["default_agent"], settings.kokoro_default_voice)
    log_event(logger, "voices.unknown_agent", agent_id=key, fallback_voice=fallback)
    return fallback
