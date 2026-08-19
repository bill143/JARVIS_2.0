"""Admin evidence export package (JSON/CSV) + audit-chain validation reporting."""

from __future__ import annotations

import csv
import hashlib
import io
import json
import sqlite3
import threading
import uuid
from datetime import UTC, datetime
from pathlib import Path


class EvidenceExporter:
    def __init__(self, db_path: Path, audit_chain, export_dir: Path):
        self.db_path = Path(db_path)
        self.audit = audit_chain
        self.export_dir = Path(export_dir)
        self.export_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS evidence_exports (id TEXT PRIMARY KEY, created_at TEXT NOT NULL, requested_by TEXT NOT NULL, "
            "tenant TEXT NOT NULL DEFAULT 'default', kind TEXT NOT NULL DEFAULT 'full', path TEXT NOT NULL DEFAULT '', "
            "record_count INTEGER NOT NULL DEFAULT 0, digest TEXT NOT NULL DEFAULT '')"
        )
        self.conn.commit()

    def validate_chain(self) -> dict:
        """Complete audit chain validation + tamper-evidence verification."""
        return self.audit.verify()

    def validation_report(self) -> dict:
        """verify() plus a human-facing summary: totals, per-category counts, date range."""
        v = self.audit.verify()
        entries = self.audit.query(limit=100000)
        by_category: dict[str, int] = {}
        for e in entries:
            by_category[e["category"]] = by_category.get(e["category"], 0) + 1
        timestamps = [e["ts"] for e in entries]
        return {
            **v,
            "by_category": by_category,
            "categories": sorted(by_category),
            "first_ts": min(timestamps) if timestamps else None,
            "last_ts": max(timestamps) if timestamps else None,
        }

    def export(self, *, requested_by: str, tenant: str | None = None, fmt: str = "json",
               start: str | None = None, end: str | None = None, category: str | None = None) -> dict:
        entries = self.audit.query(tenant=tenant, category=category, limit=100000)
        # Inclusive date filtering by ISO-8601 prefix (works for date or datetime bounds).
        if start:
            entries = [e for e in entries if e["ts"][: len(start)] >= start]
        if end:
            entries = [e for e in entries if e["ts"][: len(end)] <= end]
        verification = self.audit.verify()
        export_id = uuid.uuid4().hex[:16]
        now = datetime.now(UTC).isoformat()

        if fmt == "csv":
            buf = io.StringIO()
            writer = csv.writer(buf)
            writer.writerow(["id", "ts", "category", "actor", "tenant", "action", "hash"])
            for e in entries:
                writer.writerow([e["id"], e["ts"], e["category"], e["actor"], e["tenant"], e["action"], e["hash"]])
            payload_text = buf.getvalue()
            ext = "csv"
        else:
            payload_text = json.dumps(
                {"exported_at": now, "requested_by": requested_by, "tenant": tenant,
                 "filters": {"start": start, "end": end, "category": category},
                 "verification": verification, "entries": entries}, indent=2, default=str)
            ext = "json"

        digest = hashlib.sha256(payload_text.encode()).hexdigest()
        path = self.export_dir / f"evidence_{export_id}.{ext}"
        path.write_text(payload_text, encoding="utf-8")

        with self._lock:
            self.conn.execute(
                "INSERT INTO evidence_exports (id, created_at, requested_by, tenant, kind, path, record_count, digest) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (export_id, now, requested_by, tenant or "all", fmt, str(path), len(entries), digest))
            self.conn.commit()

        return {"id": export_id, "path": str(path), "format": fmt, "record_count": len(entries),
                "digest": digest, "verification": verification}

    def read_export(self, export_id: str) -> dict | None:
        """Return a previously generated export's content for (re-)download."""
        with self._lock:
            r = self.conn.execute(
                "SELECT path, kind FROM evidence_exports WHERE id = ?", (export_id,)).fetchone()
        if not r:
            return None
        path = Path(r[0])
        fmt = r[1]
        if not path.exists():
            return None
        return {"filename": path.name, "format": fmt, "content": path.read_text(encoding="utf-8")}

    def list_exports(self, limit: int = 50) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, created_at, requested_by, tenant, kind, path, record_count, digest FROM evidence_exports "
                "ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()
        return [{"id": r[0], "created_at": r[1], "requested_by": r[2], "tenant": r[3], "kind": r[4],
                 "path": r[5], "record_count": r[6], "digest": r[7]} for r in rows]

    def close(self) -> None:
        with self._lock:
            self.conn.close()
