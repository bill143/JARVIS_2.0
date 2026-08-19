"""Multi-agent collaboration: coordinator + specialists, message bus, arbitration."""

from jarvis_agents.bus import MessageBus
from jarvis_agents.orchestrator import MultiAgentOrchestrator

__all__ = ["MessageBus", "MultiAgentOrchestrator"]

AGENT_ROLES = ["coordinator", "researcher", "coder", "verifier", "memory_steward"]
