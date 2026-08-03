"""Credential persistence for tools and channels.

Stores credentials in ~/.openjarvis/credentials.toml with 0o600 permissions.
Thread-safe writes via lock. Sets os.environ on save for immediate effect.
"""

from __future__ import annotations

import os
import threading
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:
    import tomli as tomllib  # type: ignore[no-redef]

_LOCK = threading.Lock()
_DEFAULT_PATH = Path.home() / ".openjarvis" / "credentials.toml"

TOOL_CREDENTIALS: dict[str, list[str]] = {
    "web_search": ["TAVILY_API_KEY"],
    "image_generate": ["OPENAI_API_KEY"],
    "slack": ["SLACK_BOT_TOKEN", "SLACK_APP_TOKEN"],
    "telegram": ["TELEGRAM_BOT_TOKEN"],
    "discord": ["DISCORD_BOT_TOKEN"],
    "email": ["EMAIL_USERNAME", "EMAIL_PASSWORD"],
    "whatsapp": ["WHATSAPP_ACCESS_TOKEN", "WHATSAPP_PHONE_NUMBER_ID"],
    "signal": ["SIGNAL_CLI_PATH"],
    "google_chat": ["GOOGLE_CHAT_WEBHOOK_URL"],
    "teams": ["TEAMS_WEBHOOK_URL"],
    "bluebubbles": ["BLUEBUBBLES_SERVER_URL", "BLUEBUBBLES_PASSWORD"],
    "line": ["LINE_CHANNEL_ACCESS_TOKEN", "LINE_CHANNEL_SECRET"],
    "viber": ["VIBER_AUTH_TOKEN"],
    "messenger": ["MESSENGER_PAGE_ACCESS_TOKEN", "MESSENGER_VERIFY_TOKEN"],
    "reddit": [
        "REDDIT_CLIENT_ID",
        "REDDIT_CLIENT_SECRET",
        "REDDIT_USERNAME",
        "REDDIT_PASSWORD",
    ],
    "mastodon": ["MASTODON_ACCESS_TOKEN", "MASTODON_API_BASE_URL"],
    "twitch": ["TWITCH_TOKEN", "TWITCH_CHANNEL"],
    "matrix": ["MATRIX_HOMESERVER", "MATRIX_ACCESS_TOKEN"],
    "mattermost": ["MATTERMOST_URL", "MATTERMOST_TOKEN"],
    "zulip": ["ZULIP_EMAIL", "ZULIP_API_KEY", "ZULIP_SITE"],
    "rocketchat": ["ROCKETCHAT_URL", "ROCKETCHAT_USER_ID", "ROCKETCHAT_AUTH_TOKEN"],
    "xmpp": ["XMPP_JID", "XMPP_PASSWORD"],
    "feishu": ["FEISHU_APP_ID", "FEISHU_APP_SECRET"],
    "nostr": ["NOSTR_PRIVATE_KEY"],
}


# Section under which ad-hoc secrets (e.g. channel-binding credentials that are
# not part of TOOL_CREDENTIALS) are persisted.
_SECRETS_SECTION = "_secrets"


def _toml_escape(value: str) -> str:
    """Escape a string for a TOML basic (double-quoted) string literal."""
    out = value.replace("\\", "\\\\").replace('"', '\\"')
    return (
        out.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    )


def _write_credentials(p: Path, creds: dict[str, dict[str, str]]) -> None:
    """Serialize ``creds`` to a 0600 TOML file at ``p`` (escaping values)."""
    p.parent.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []
    for section, kvs in creds.items():
        lines.append(f"[{section}]")
        for k, v in kvs.items():
            lines.append(f'{k} = "{_toml_escape(str(v))}"')
        lines.append("")
    p.write_text("\n".join(lines))
    os.chmod(p, 0o600)


def load_credentials(path: Path | None = None) -> dict[str, dict[str, str]]:
    """Load credentials from TOML file."""
    p = Path(path) if path else _DEFAULT_PATH
    if not p.exists():
        return {}
    with open(p, "rb") as f:
        return tomllib.load(f)


def save_credential(
    tool_name: str,
    key: str,
    value: str,
    *,
    path: Path | None = None,
) -> None:
    """Save a single credential key, validate, write file, and set os.environ."""
    allowed = TOOL_CREDENTIALS.get(tool_name, [])
    if key not in allowed:
        raise ValueError(f"Unknown credential key '{key}' for tool '{tool_name}'")
    stripped = value.strip()
    if not stripped:
        raise ValueError("Credential value must not be empty")

    p = Path(path) if path else _DEFAULT_PATH
    with _LOCK:
        creds = load_credentials(path=p)
        if tool_name not in creds:
            creds[tool_name] = {}
        creds[tool_name][key] = stripped
        _write_credentials(p, creds)

    os.environ[key] = stripped


def set_secret(
    var_name: str,
    value: str,
    *,
    section: str = _SECRETS_SECTION,
    path: Path | None = None,
) -> None:
    """Persist an arbitrary secret env var to credentials.toml (0600) + os.environ.

    Unlike :func:`save_credential` this does not validate against
    ``TOOL_CREDENTIALS`` — it backs dynamically-named secrets such as
    per-binding channel credentials that are stored out of the database.
    """
    p = Path(path) if path else _DEFAULT_PATH
    with _LOCK:
        creds = load_credentials(path=p)
        creds.setdefault(section, {})[var_name] = value
        _write_credentials(p, creds)
    os.environ[var_name] = value


def get_credential_status(tool_name: str) -> dict[str, bool]:
    """Return {KEY: bool} for each required key indicating if set in env."""
    keys = TOOL_CREDENTIALS.get(tool_name, [])
    return {k: bool(os.environ.get(k)) for k in keys}


def inject_credentials(path: Path | None = None) -> None:
    """Load credentials.toml and inject into os.environ. Call at server startup."""
    creds = load_credentials(path=path)
    for _tool, kvs in creds.items():
        for k, v in kvs.items():
            if k not in os.environ:
                os.environ[k] = v


def load_dotenv(path: Path | None = None) -> int:
    """Load ``KEY=VALUE`` lines from a ``.env`` file into os.environ.

    Existing environment variables win (a real env var always overrides the
    file), so ``.env`` is a fallback for local/self-hosted deployments. Blank
    lines, ``#`` comments, and an optional ``export`` prefix are tolerated;
    surrounding single/double quotes on the value are stripped. Returns the
    number of variables set.
    """
    p = Path(path) if path else Path.cwd() / ".env"
    if not p.exists():
        return 0
    count = 0
    for raw_line in p.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        if line.startswith("export "):
            line = line[len("export ") :]
        key, _, val = line.partition("=")
        key = key.strip()
        val = val.strip()
        if len(val) >= 2 and val[0] == val[-1] and val[0] in ("'", '"'):
            val = val[1:-1]
        if key and key not in os.environ:
            os.environ[key] = val
            count += 1
    return count


def bootstrap_secrets(
    *,
    dotenv_path: Path | None = None,
    creds_path: Path | None = None,
) -> None:
    """Load secrets from ``.env`` then ``credentials.toml`` into os.environ.

    Idempotent and safe to call on every server startup: real environment
    variables are never overwritten, and missing files are a no-op.
    """
    load_dotenv(dotenv_path)
    inject_credentials(creds_path)
