"""Lightweight rollback-safe SQLite migration runner (Alembic-equivalent).

Each migration module in infra/migrations/ exposes:
    VERSION: int
    NAME: str
    def UP(conn: sqlite3.Connection) -> None
    def DOWN(conn: sqlite3.Connection) -> None

Applied versions are tracked in the schema_migrations table. Migrations are
idempotent (all DDL uses IF NOT EXISTS) so re-running is always safe, preserving
Phase 1 data.

Usage:
    python -m jarvis_shared.migrate up
    python -m jarvis_shared.migrate down --to 3
    python -m jarvis_shared.migrate status
"""

from __future__ import annotations

import argparse
import importlib.util
import sqlite3
import sys
from datetime import UTC, datetime
from pathlib import Path

from jarvis_shared.config import get_settings
from jarvis_shared.logging import get_logger, log_event

logger = get_logger("jarvis.migrate")


def _migrations_dir() -> Path:
    # packages/shared/jarvis_shared/migrate.py -> repo root -> infra/migrations
    root = Path(__file__).resolve().parents[3]
    return root / "infra" / "migrations"


def discover_migrations() -> list:
    directory = _migrations_dir()
    modules = []
    if not directory.exists():
        return modules
    for path in sorted(directory.glob("m*.py")):
        spec = importlib.util.spec_from_file_location(path.stem, path)
        if not spec or not spec.loader:
            continue
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        if hasattr(module, "VERSION") and hasattr(module, "UP"):
            modules.append(module)
    modules.sort(key=lambda m: m.VERSION)
    return modules


def _connect(db_path: Path) -> sqlite3.Connection:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db_path))
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=5000")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS schema_migrations (
            version INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            applied_at TEXT NOT NULL
        )
        """
    )
    conn.commit()
    return conn


def applied_versions(conn: sqlite3.Connection) -> set[int]:
    return {row[0] for row in conn.execute("SELECT version FROM schema_migrations").fetchall()}


def migrate_up(db_path: Path | None = None, target: int | None = None) -> list[int]:
    settings = get_settings()
    db_path = db_path or settings.sqlite_path
    conn = _connect(db_path)
    done = applied_versions(conn)
    applied: list[int] = []
    try:
        for module in discover_migrations():
            if module.VERSION in done:
                continue
            if target is not None and module.VERSION > target:
                break
            module.UP(conn)
            conn.execute(
                "INSERT OR REPLACE INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)",
                (module.VERSION, module.NAME, datetime.now(UTC).isoformat()),
            )
            conn.commit()
            applied.append(module.VERSION)
            log_event(logger, "migration.up", version=module.VERSION, name=module.NAME)
    finally:
        conn.close()
    return applied


def migrate_down(db_path: Path | None = None, target: int = 0) -> list[int]:
    settings = get_settings()
    db_path = db_path or settings.sqlite_path
    conn = _connect(db_path)
    done = applied_versions(conn)
    reverted: list[int] = []
    try:
        for module in sorted(discover_migrations(), key=lambda m: m.VERSION, reverse=True):
            if module.VERSION in done and module.VERSION > target:
                if hasattr(module, "DOWN"):
                    module.DOWN(conn)
                conn.execute("DELETE FROM schema_migrations WHERE version = ?", (module.VERSION,))
                conn.commit()
                reverted.append(module.VERSION)
                log_event(logger, "migration.down", version=module.VERSION, name=module.NAME)
    finally:
        conn.close()
    return reverted


def status(db_path: Path | None = None) -> list[dict]:
    settings = get_settings()
    db_path = db_path or settings.sqlite_path
    conn = _connect(db_path)
    try:
        done = applied_versions(conn)
        return [
            {"version": m.VERSION, "name": m.NAME, "applied": m.VERSION in done}
            for m in discover_migrations()
        ]
    finally:
        conn.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="JARVIS migration runner")
    parser.add_argument("command", choices=["up", "down", "status"], nargs="?", default="up")
    parser.add_argument("--to", type=int, default=None, help="target version")
    args = parser.parse_args(argv)

    if args.command == "up":
        applied = migrate_up(target=args.to)
        print(f"Applied migrations: {applied or 'none (already up to date)'}")
    elif args.command == "down":
        reverted = migrate_down(target=args.to if args.to is not None else 0)
        print(f"Reverted migrations: {reverted or 'none'}")
    else:
        for row in status():
            mark = "x" if row["applied"] else " "
            print(f"[{mark}] {row['version']:04d} {row['name']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
