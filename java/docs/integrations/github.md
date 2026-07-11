# GitHub integration (Phase 5 — reference implementation)

The GitHub plugin is the reference Phase 5 integration. Every later integration reuses the same
scaffold: a **manifest** with per-action **risk tiers**, a **transport seam** for the network
boundary, and the shared **permission gate** that confirms MUTATING/DESTRUCTIVE actions before they
run. No third-party SDK is used — only the JDK `HttpClient` and Jackson, per the platform whitelist.

## Auth setup

Authentication is a **fine-grained Personal Access Token (PAT)** with the minimum scopes for the
actions you enable.

1. Create a fine-grained PAT at **GitHub → Settings → Developer settings → Personal access tokens →
   Fine-grained tokens**. Grant only what you need:
   - Read-only use: `Contents: Read-only`, `Issues: Read-only`, `Pull requests: Read-only`,
     `Metadata: Read-only`.
   - To also create issues / comment / open PRs: raise `Issues` and `Pull requests` to
     `Read and write`.
2. Provide the token to JARVIS via an environment variable — **referenced by name only, never
   stored in the repo or logged**:

   ```
   setx JARVIS_GITHUB_TOKEN "<your-token>"      # Windows (new terminals pick it up)
   ```

   The token is read at startup, held only in memory, and never printed, echoed, or included in
   any error message. If the variable is unset the plugin stays **dormant**: the tools are still
   registered but each returns a graceful "not configured" error instead of failing.

## Actions and risk tiers

| Tool | Tier | Confirmation before run? |
|---|---|---|
| `github_list_repos` | READ_ONLY | no |
| `github_list_issues` | READ_ONLY | no |
| `github_get_issue` | READ_ONLY | no |
| `github_list_pull_requests` | READ_ONLY | no |
| `github_get_pull_request` | READ_ONLY | no |
| `github_read_file` | READ_ONLY | no |
| `github_list_commits` | READ_ONLY | no |
| `github_create_issue` | MUTATING | **yes** — user must confirm |
| `github_comment_issue` | MUTATING | **yes** — user must confirm |
| `github_open_pull_request` | MUTATING | **yes** — user must confirm |

The tiers are declared in `integrations/src/main/resources/manifests/github.json` and enforced by
the governance layer (`HealthTrackingTool` → `AuditedTool` → `AuthorizingTool`). A MUTATING call is
routed to the `PermissionGate`; a denied or timed-out confirmation is **never executed** and the
network is never touched.

### Not yet implemented (held at CHECKPOINT 1)

The DESTRUCTIVE actions — **merge PR, close issue/PR, delete branch** — are intentionally absent
from this pass. They will be added after the checkpoint, each classified `DESTRUCTIVE` and gated the
same way.

## Design

- `GitHubTransport` — the network seam (`method`, `path`, `jsonBody` → `GitHubResponse`).
- `HttpGitHubTransport` — the real JDK `HttpClient` transport; dormant without a token.
- `GitHubClient` — typed, one method per REST endpoint (api.github.com, API version 2022-11-28).
- `GitHubPlugin` — exposes the tools; delegates to `GitHubClient`; failures become failed
  `ToolResult`s, never throws.

Tests exercise the full path with a fake transport (no token, no network): `GitHubClientTest`
(integrations) covers the client and dormancy; `GitHubPluginGatedTest` (app) proves READ_ONLY runs
ungated while a MUTATING call is blocked until confirmed.
