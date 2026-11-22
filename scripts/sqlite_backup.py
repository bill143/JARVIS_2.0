"""Consistent SQLite copy via the backup API (safe under WAL). Usage: src dest."""

from __future__ import annotations

import sqlite3
import sys


def main() -> int:
    src_path, dest_path = sys.argv[1], sys.argv[2]
    src = sqlite3.connect(src_path)
    dest = sqlite3.connect(dest_path)
    try:
        src.backup(dest)
    finally:
        dest.close()
        src.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
