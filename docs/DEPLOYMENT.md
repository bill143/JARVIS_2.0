# Deployment Guide

This guide covers running JARVIS in production: containerized, behind a
reverse proxy with automatic TLS, on an isolated network.

- [1. Prerequisites](#1-prerequisites)
- [2. Configure secrets and domain](#2-configure-secrets-and-domain)
- [3. Run with docker-compose + Caddy (TLS)](#3-run-with-docker-compose--caddy-tls)
- [4. Enabling the auth gateway](#4-enabling-the-auth-gateway)
- [5. Obsidian vault git-sync](#5-obsidian-vault-git-sync)
- [6. Operations](#6-operations)

---

## 1. Prerequisites

- Docker Engine 24+ and the Compose plugin (`docker compose version`).
- A server with ports **80** and **443** reachable from the internet (for
  Let's Encrypt HTTP-01 / TLS-ALPN challenges).
- A DNS **A/AAAA record** pointing your domain at the server (for real certs).

The architecture:

```
                   ┌─────────────────────── jarvis_net (isolated bridge) ──┐
  internet ──443──▶│  caddy  ──http──▶  app (jarvis, uvicorn :8000)         │
                   │  (auto Let's Encrypt TLS)   no published host port     │
                   └───────────────────────────────────────────────────────┘
```

The **app publishes no host port** — it is only reachable through Caddy on the
private `jarvis_net` bridge. Caddy terminates TLS and reverse-proxies to the
app.

---

## 2. Configure secrets and domain

Copy the example env file and fill it in:

```bash
cp .env.example .env
```

Set at minimum:

| Variable | Purpose |
|---|---|
| `JARVIS_DOMAIN` | Public domain Caddy serves (e.g. `jarvis.example.com`). Use `localhost` for local testing. |
| `ACME_EMAIL` | Email for the Let's Encrypt account (recommended on a real domain). |
| `OPENJARVIS_AUTH_ENABLED` | `true` to require login (default in compose). |
| `OPENJARVIS_ADMIN_USER` / `OPENJARVIS_ADMIN_PASSWORD` | Seed the first admin on first boot. |
| provider / channel keys | Any secrets your deployment uses (see the file). |

`.env` is gitignored and is **never** baked into the image — it is read at
runtime by compose. Secrets are also resolvable from the container's real
environment or the mounted `credentials.toml`.

---

## 3. Run with docker-compose + Caddy (TLS)

```bash
docker compose up -d --build
```

- First boot builds the image (compiles the native Rust module) — this takes a
  few minutes once.
- Caddy automatically obtains and renews a Let's Encrypt certificate for
  `JARVIS_DOMAIN`. With `JARVIS_DOMAIN=localhost` it uses its own locally
  trusted internal CA instead, so the same config works for local testing.

Check status and logs:

```bash
docker compose ps
docker compose logs -f app
docker compose logs -f caddy
```

Then browse to `https://<JARVIS_DOMAIN>/`.

To validate the config without starting anything:

```bash
docker compose config
```

---

## 4. Enabling the auth gateway

The compose file sets `OPENJARVIS_AUTH_ENABLED=true`. On first boot the app
seeds an admin user from `OPENJARVIS_ADMIN_USER` / `OPENJARVIS_ADMIN_PASSWORD`
(only if no users exist yet). Every route except `/login` and `/health`
then requires a valid session cookie; browsers are redirected to `/login`,
API clients receive `401`.

Rotate the admin password later from inside the container, or by clearing the
users table in the mounted `auth.db` and re-seeding via the env vars.

---

## 5. Obsidian vault git-sync

Mount your vault and enable sync so notes auto-commit/pull and re-index
without a rebuild:

1. Set `VAULT_PATH` in `.env` to the host path of your vault (mounted into the
   container at `/vault`).
2. Configure `[vault]` in `~/.openjarvis/config.toml` (the mounted
   `jarvis_data` volume) or via the runtime API:

   ```bash
   curl -X POST https://<domain>/v1/vault/config \
     -H 'Content-Type: application/json' \
     -d '{"enabled": true, "path": "/vault", "poll_interval": 30}'
   ```

3. Trigger an immediate sync:

   ```bash
   curl -X POST https://<domain>/v1/vault/sync
   ```

The watcher commits local edits, rebase-pulls the remote, and re-indexes the
vault into the knowledge store on each change. For pull/push to a remote,
provide the vault's git credentials in the mounted volume as usual.

---

## 6. Operations

```bash
# Update to a new build
git pull && docker compose up -d --build

# Restart just the app
docker compose restart app

# Tear down (keeps named volumes / certs / data)
docker compose down

# Tear down and delete volumes (DESTROYS data + certs)
docker compose down -v
```

Persistent state lives in named volumes:

- `jarvis_data` → `/home/jarvis/.openjarvis` (config, `credentials.toml`, sqlite stores)
- `caddy_data` → Let's Encrypt certificates and ACME account
- `caddy_config` → Caddy's autosave config

The container runs as the non-root `jarvis` user and exposes a Docker
healthcheck against `/health`.
