"""Tool schema validation, allowlist enforcement, and the Python sandbox."""

import pytest

from jarvis_shared.errors import ToolNotAllowed, ToolValidationError
from jarvis_tools import DEFAULT_ALLOWLIST, build_default_registry
from jarvis_tools.python_exec import run_python_sandboxed


@pytest.fixture()
def registry(settings):
    return build_default_registry(settings)


def test_all_default_tools_expose_json_schemas(registry):
    schemas = registry.schemas()
    names = {s["name"] for s in schemas}
    assert names == DEFAULT_ALLOWLIST
    for schema in schemas:
        assert schema["description"]
        assert schema["parameters"]["type"] == "object"
        assert isinstance(schema["parameters"].get("properties", {}), dict)


def test_missing_required_argument_rejected(registry):
    with pytest.raises(ToolValidationError):
        registry.validate_args("python_exec", {})


def test_wrong_type_rejected(registry):
    with pytest.raises(ToolValidationError):
        registry.validate_args("python_exec", {"code": 123})
    with pytest.raises(ToolValidationError):
        registry.validate_args("web_search", {"query": "x", "max_results": "five"})


def test_unknown_argument_rejected(registry):
    with pytest.raises(ToolValidationError):
        registry.validate_args("file_read", {"path": "a.txt", "mode": "rb"})


async def test_unknown_tool_not_allowed(registry):
    with pytest.raises(ToolNotAllowed):
        await registry.execute("shell_exec", {"cmd": "dir"})


async def test_allowlist_enforced(settings):
    registry = build_default_registry(settings)
    registry.allowlist = {"web_search"}  # tighten allowlist below what's registered
    with pytest.raises(ToolNotAllowed):
        await registry.execute("python_exec", {"code": "print(1)"})


async def test_python_exec_runs_and_returns_output(registry, settings):
    result = await registry.execute("python_exec", {"code": "print(2 + 2)"}, session_id="unit")
    assert result.status == "ok"
    assert result.output["stdout"].strip() == "4"


def test_sandbox_blocks_dangerous_imports():
    result = run_python_sandboxed("import os\nprint(os.getcwd())", timeout=10)
    assert result["returncode"] != 0
    assert "blocked" in result["stderr"]


def test_sandbox_timeout_kills_runaway_code():
    result = run_python_sandboxed("while True:\n    pass", timeout=2)
    assert result["timed_out"] is True
