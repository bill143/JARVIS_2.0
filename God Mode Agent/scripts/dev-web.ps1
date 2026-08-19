# Start the Next.js web app dev server.
. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot

$webPort = Get-DotEnvValue -Key "WEB_PORT" -Default "3000"
$backend = Get-DotEnvValue -Key "BACKEND_PUBLIC_URL" -Default "http://127.0.0.1:8000"
if (-not $env:NEXT_PUBLIC_BACKEND_URL) { $env:NEXT_PUBLIC_BACKEND_URL = $backend }

Push-Location -LiteralPath (Join-Path $root "apps\web")
try {
    Write-Host "Starting web UI on http://127.0.0.1:${webPort} (backend: $env:NEXT_PUBLIC_BACKEND_URL)" -ForegroundColor Cyan
    if (Get-Command pnpm -ErrorAction SilentlyContinue) { pnpm dev --port $webPort }
    else { npm run dev -- --port $webPort }
    exit $LASTEXITCODE
} finally { Pop-Location }
