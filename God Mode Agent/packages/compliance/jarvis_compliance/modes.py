"""Compliance-mode toggles: audit strict mode, restricted tool mode, export controls."""

from __future__ import annotations


class ComplianceModes:
    """Runtime-adjustable compliance toggles derived from settings + overrides."""

    RESTRICTED_TOOLS = {"web_search", "file_read"}

    def __init__(self, settings):
        self.settings = settings
        self._overrides: dict[str, bool] = {}

    def _get(self, key: str, default: bool) -> bool:
        return self._overrides.get(key, default)

    @property
    def compliance_mode(self) -> bool:
        return self._get("compliance_mode", self.settings.compliance_mode)

    @property
    def audit_strict_mode(self) -> bool:
        return self._get("audit_strict_mode", self.settings.audit_strict_mode)

    @property
    def restricted_tool_mode(self) -> bool:
        # In compliance mode, restrict tools to a safe subset unless explicitly overridden.
        return self._get("restricted_tool_mode", self.compliance_mode)

    @property
    def export_controls(self) -> bool:
        return self._get("export_controls", self.compliance_mode)

    def tool_permitted(self, tool: str) -> bool:
        if not self.restricted_tool_mode:
            return True
        return tool in self.RESTRICTED_TOOLS

    def set(self, key: str, value: bool) -> None:
        if key not in ("compliance_mode", "audit_strict_mode", "restricted_tool_mode", "export_controls"):
            raise ValueError(f"unknown compliance toggle '{key}'")
        self._overrides[key] = value

    def status(self) -> dict:
        return {
            "compliance_mode": self.compliance_mode,
            "audit_strict_mode": self.audit_strict_mode,
            "restricted_tool_mode": self.restricted_tool_mode,
            "export_controls": self.export_controls,
        }
