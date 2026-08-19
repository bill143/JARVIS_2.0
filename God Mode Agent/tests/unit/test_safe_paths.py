"""Safe workspace path enforcement."""

import pytest

from jarvis_shared.errors import UnsafePath
from jarvis_shared.paths import resolve_safe


def test_normal_relative_path_resolves_inside_workspace(tmp_path):
    result = resolve_safe(tmp_path, "notes/today.txt")
    assert result == (tmp_path / "notes" / "today.txt").resolve()


def test_traversal_is_rejected(tmp_path):
    with pytest.raises(UnsafePath):
        resolve_safe(tmp_path, "..\\escape.txt")
    with pytest.raises(UnsafePath):
        resolve_safe(tmp_path, "../../etc/passwd")
    with pytest.raises(UnsafePath):
        resolve_safe(tmp_path, "a/../../b.txt")


def test_absolute_and_drive_paths_are_rejected(tmp_path):
    with pytest.raises(UnsafePath):
        resolve_safe(tmp_path, "C:\\Windows\\system32\\config")
    with pytest.raises(UnsafePath):
        resolve_safe(tmp_path, "/etc/passwd")


def test_empty_path_is_rejected(tmp_path):
    with pytest.raises(UnsafePath):
        resolve_safe(tmp_path, "")


def test_file_tools_enforce_workspace(tmp_path, settings):
    from jarvis_shared.errors import UnsafePath as UP
    from jarvis_tools.files import file_read, file_write

    workspace = settings.workspace_path
    result = file_write(workspace, "safe.txt", "hello")
    assert result["written"] is True
    assert file_read(workspace, "safe.txt")["content"] == "hello"
    with pytest.raises(UP):
        file_write(workspace, "..\\evil.txt", "nope")
    with pytest.raises(UP):
        file_read(workspace, "..\\..\\secrets.txt")
