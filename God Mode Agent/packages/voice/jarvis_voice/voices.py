"""Per-agent voice resolution from configs/voices.yaml (ECHO Command Stage 2)."""

from __future__ import annotations

import threading
from pathlib import Path

import yaml

from jarvis_shared.config import Settings
from jarvis_shared.logging import get_logger, log_event

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


def load_voice_map(settings: Settings) -> dict:
    """Load and cache the agent->voice map. Shape: {agents, default_agent, path}."""
    global _cached
    with _lock:
        if _cached is not None:
            return _cached
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
            }
            log_event(logger, "voices.loaded", path=str(path), agents=sorted(agents))
            return _cached
        log_event(logger, "voices.config_missing", tried=[str(p) for p in _candidate_paths(settings)])
        _cached = {"agents": {}, "default_agent": "JARVIS", "path": ""}
        return _cached


def resolve_voice(agent_id: str | None, settings: Settings) -> str:
    """Map an agent id to its Kokoro voice; unknown agents use the default agent's voice."""
    voice_map = load_voice_map(settings)
    agents: dict[str, str] = voice_map["agents"]
    key = (agent_id or voice_map["default_agent"]).upper()
    if key in agents:
        return agents[key]
    fallback = agents.get(voice_map["default_agent"], settings.kokoro_default_voice)
    log_event(logger, "voices.unknown_agent", agent_id=key, fallback_voice=fallback)
    return fallback
