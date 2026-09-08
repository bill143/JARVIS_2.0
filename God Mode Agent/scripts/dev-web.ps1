# Start the Next.js web app dev server.
. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot

$webPort = Get-DotEnvValue -Key "WEB_PORT" -Default "3000"
# The browser always calls the same origin at /api; Next rewrites that to the
# backend (apps\web\next.config.mjs). BACKEND_ORIGIN stays server-side so no
# absolute host is baked into the client bundle and the tailnet keeps working.
$backend = Get-DotEnvValue -Key "BACKEND_PUBLIC_URL" -Default "http://127.0.0.1:8000"
if (-not $env:BACKEND_ORIGIN) { $env:BACKEND_ORIGIN = $backend }

Push-Location -LiteralPath (Join-Path $root "apps\web")
try {
    Write-Host "Starting web UI on http://127.0.0.1:${webPort} (proxying /api -> $env:BACKEND_ORIGIN)" -ForegroundColor Cyan
    if (Get-Command pnpm -ErrorAction SilentlyContinue) { pnpm dev --port $webPort }
    else { npm run dev -- --port $webPort }
    exit $LASTEXITCODE
} finally { Pop-Location }
