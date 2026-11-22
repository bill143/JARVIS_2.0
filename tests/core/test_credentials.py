"""Tests for credential persistence module."""

import os

import pytest

from openjarvis.core.credentials import (
    bootstrap_secrets,
    get_credential_status,
    inject_credentials,
    load_credentials,
    load_dotenv,
    save_credential,
    set_secret,
)


@pytest.fixture
def cred_path(tmp_path):
    return tmp_path / "credentials.toml"


def test_save_and_load(cred_path):
    save_credential("web_search", "TAVILY_API_KEY", "tvly-123", path=cred_path)
    creds = load_credentials(path=cred_path)
    assert creds["web_search"]["TAVILY_API_KEY"] == "tvly-123"


def test_save_sets_env(cred_path, monkeypatch):
    monkeypatch.delenv("TAVILY_API_KEY", raising=False)
    save_credential("web_search", "TAVILY_API_KEY", "tvly-abc", path=cred_path)
    assert os.environ["TAVILY_API_KEY"] == "tvly-abc"


def test_save_rejects_unknown_key(cred_path):
    with pytest.raises(ValueError, match="Unknown credential key"):
        save_credential("web_search", "BOGUS_KEY", "val", path=cred_path)


def test_save_rejects_empty_value(cred_path):
    with pytest.raises(ValueError, match="empty"):
        save_credential("web_search", "TAVILY_API_KEY", "  ", path=cred_path)


def test_get_status(cred_path, monkeypatch):
    monkeypatch.setenv("TAVILY_API_KEY", "tvly-x")
    status = get_credential_status("web_search")
    assert status["TAVILY_API_KEY"] is True


def test_get_status_missing(monkeypatch):
    monkeypatch.delenv("TAVILY_API_KEY", raising=False)
    status = get_credential_status("web_search")
    assert status["TAVILY_API_KEY"] is False


def test_file_permissions(cred_path):
    save_credential("web_search", "TAVILY_API_KEY", "tvly-x", path=cred_path)
    mode = oct(cred_path.stat().st_mode & 0o777)
    assert mode == "0o600"


def test_save_credential_escapes_special_chars(cred_path):
    # A value with a quote/backslash must still round-trip through TOML.
    tricky = 'a"b\\c'
    save_credential("web_search", "TAVILY_API_KEY", tricky, path=cred_path)
    creds = load_credentials(path=cred_path)
    assert creds["web_search"]["TAVILY_API_KEY"] == tricky


def test_set_secret_persists_and_sets_env(cred_path, monkeypatch):
    monkeypatch.delenv("OJ_BINDING_X_TOKEN", raising=False)
    set_secret("OJ_BINDING_X_TOKEN", "sekret-value", path=cred_path)
    assert os.environ["OJ_BINDING_X_TOKEN"] == "sekret-value"
    creds = load_credentials(path=cred_path)
    # Stored under the internal _secrets section, 0600.
    assert creds["_secrets"]["OJ_BINDING_X_TOKEN"] == "sekret-value"
    assert oct(cred_path.stat().st_mode & 0o777) == "0o600"


def test_load_dotenv(tmp_path, monkeypatch):
    env_file = tmp_path / ".env"
    env_file.write_text(
        "# comment\n"
        "\n"
        "FOO=bar\n"
        'QUOTED="quoted-value"\n'
        "export EXPORTED=exp\n"
        "PREEXISTING=fromfile\n"
    )
    monkeypatch.delenv("FOO", raising=False)
    monkeypatch.delenv("QUOTED", raising=False)
    monkeypatch.delenv("EXPORTED", raising=False)
    monkeypatch.setenv("PREEXISTING", "fromenv")
    count = load_dotenv(env_file)
    assert os.environ["FOO"] == "bar"
    assert os.environ["QUOTED"] == "quoted-value"
    assert os.environ["EXPORTED"] == "exp"
    # A real env var is never overwritten by the file.
    assert os.environ["PREEXISTING"] == "fromenv"
    assert count == 3


def test_load_dotenv_missing_file_is_noop(tmp_path):
    assert load_dotenv(tmp_path / "nope.env") == 0


def test_inject_credentials_sets_env(cred_path, monkeypatch):
    monkeypatch.delenv("TAVILY_API_KEY", raising=False)
    save_credential("web_search", "TAVILY_API_KEY", "tvly-inj", path=cred_path)
    monkeypatch.delenv("TAVILY_API_KEY", raising=False)
    inject_credentials(path=cred_path)
    assert os.environ["TAVILY_API_KEY"] == "tvly-inj"


def test_bootstrap_secrets_loads_both(tmp_path, cred_path, monkeypatch):
    env_file = tmp_path / ".env"
    env_file.write_text("DOTENV_ONLY=fromdotenv\n")
    monkeypatch.delenv("DOTENV_ONLY", raising=False)
    monkeypatch.delenv("TAVILY_API_KEY", raising=False)
    save_credential("web_search", "TAVILY_API_KEY", "tvly-boot", path=cred_path)
    monkeypatch.delenv("TAVILY_API_KEY", raising=False)
    bootstrap_secrets(dotenv_path=env_file, creds_path=cred_path)
    assert os.environ["DOTENV_ONLY"] == "fromdotenv"
    assert os.environ["TAVILY_API_KEY"] == "tvly-boot"
