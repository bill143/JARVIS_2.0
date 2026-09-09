# JARVIS God Mode Agent — Compliance

Phase 3 governance-ready controls. See also [SECURITY.md](SECURITY.md),
[RUNBOOK.md](RUNBOOK.md), and [ARCHITECTURE_PHASE3.md](ARCHITECTURE_PHASE3.md).

## Compliance modes

Toggle at runtime (admin) via `POST /compliance/toggle {key,value}` or set defaults
in `.env`. Query with `GET /compliance/status`.

| Toggle | Effect |
|---|---|
| `compliance_mode` | Master switch; enables the stricter defaults below and restricts sensitive-task routing to approved providers only. |
| `audit_strict_mode` | Every governance action must be recorded in the hash-chained audit log (no best-effort skips). |
| `restricted_tool_mode` | Tools limited to a safe allowlist (`web_search`, `file_read`); high-risk tools (`python_exec`, `file_write`) blocked. On by default when compliance_mode is on. |
| `export_controls` | Gates evidence/log/memory export behind admin + audit. |

## Data retention

Per-data-class policies (`GET/POST /compliance/retention`), seeded defaults:

| Data class | Retention (days) | Deletion window | Region |
|---|---|---|---|
| audit | 365 | 0 (immutable) | global |
| chat | 180 | 30 | global |
| memory | 90 | 30 | global |
| rag | 365 | 30 | global |
| routing_usage | 180 | 7 | global |

`region` is a regional-data-handling flag (e.g., `eu`, `us`) for data-residency
policies. `deletion_window_days` is the grace period before hard deletion.
`DATA_RETENTION_DAYS` sets the default for unlisted classes.

## Key management (KMS)

`jarvis_compliance.get_kms()` returns a provider implementing
`encrypt/decrypt/rotate` behind a stable interface. The **local** provider (default)
derives keys via PBKDF2 and is fully offline; a **cloud KMS** (AWS/GCP/Azure) plugs
in behind the same interface. Rotation bumps the key version; data sealed under an
earlier version still decrypts. Set `JARVIS_KMS_MASTER` for the local master key.

## Audit chain + tamper evidence

The governance audit log is SHA-256 hash-chained (each entry hashes the previous
hash + its canonical JSON). Validation walks the chain and reports the first broken
link:

- API: `GET /compliance/validate` (admin) / `GET /audit/verify` (admin).
- CLI: `.\scripts\compliance-check.ps1` runs migration status, audit validation, and the compliance test suite.

## Evidence export

Admin evidence packages bundle the full audit log plus a verification result and a
SHA-256 digest, written to `EVIDENCE_EXPORT_DIR` and recorded in `evidence_exports`.

- API: `POST /compliance/export?fmt=json|csv` (admin); `GET /compliance/exports` lists prior packages.
- CLI: `.\scripts\export-evidence.ps1 [-Format csv]`.

## Secure-config checklist (compliance)

- [ ] `COMPLIANCE_MODE=true` in regulated environments.
- [ ] `AUDIT_STRICT_MODE=true`; `ENABLE_AUDIT_LOG_HASH_CHAIN=true`.
- [ ] Retention policies reviewed per data class and region.
- [ ] KMS master key (or cloud KMS) provisioned via secret manager, rotated on schedule.
- [ ] Evidence exports stored in a controlled, access-logged location.
- [ ] Sensitive tasks confined to `approved_providers` (see config).
- [ ] Run `.\scripts\compliance-check.ps1` in CI before release.

## Data-subject controls (privacy)

Memory governance provides per-user controls: view (`GET /memory/items`), correct
(`/edit`), delete/forget (`/forget`), and export (`GET /memory/export`) — supporting
access/rectification/erasure/portability requests. Memory is tenant- and
user-namespaced; cross-tenant reads are impossible by construction.
