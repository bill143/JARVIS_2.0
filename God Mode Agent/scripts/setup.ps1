# One-shot environment setup for JARVIS God Mode Agent (Windows / PowerShell 7+).
. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot
Set-Location -LiteralPath $root

Write-Host "== JARVIS setup ==" -ForegroundColor Cyan
Write-Host "Project root: $root"

# --- Verify Python 3.11+ ---
$pythonOk = $false
try {
    $ver = & python -c "import sys; print('%d.%d' % sys.version_info[:2])" 2>$null
    if ($ver) {
        $parts = $ver.Split('.')
        if ([int]$parts[0] -gt 3 -or ([int]$parts[0] -eq 3 -and [int]$parts[1] -ge 11)) { $pythonOk = $true }
    }
} catch {}
if (-not $pythonOk) { Write-Error "Python 3.11+ is required on PATH (found: '$ver')." }
Write-Host "Python $ver OK"

# --- Verify Node 20+ ---
$nodeOk = $false
try {
    $nodeVer = (& node --version) -replace '^v', ''
    if ([int]$nodeVer.Split('.')[0] -ge 20) { $nodeOk = $true }
} catch {}
if (-not $nodeOk) { Write-Error "Node.js 20+ LTS is required on PATH (found: '$nodeVer')." }
Write-Host "Node $nodeVer OK"

# --- Create virtual env (.venv) ---
$useUv = [bool](Get-Command uv -ErrorAction SilentlyContinue)
if (-not (Test-Path -LiteralPath (Join-Path $root ".venv"))) {
    if ($useUv) { uv venv .venv } else { python -m venv .venv }
    if ($LASTEXITCODE -ne 0) { Write-Error "Failed to create .venv" }
}
$venvPy = Join-Path $root ".venv\Scripts\python.exe"
Write-Host "venv: $venvPy (uv available: $useUv)"

# --- Install Python deps (uv preferred, pip fallback) ---
if ($useUv) {
    uv pip install -r requirements.txt --python "$venvPy"
    if ($LASTEXITCODE -ne 0) { Write-Error "Core dependency install failed (uv)" }
} else {
    & "$venvPy" -m pip install --upgrade pip
    & "$venvPy" -m pip install -r requirements.txt
    if ($LASTEXITCODE -ne 0) { Write-Error "Core dependency install failed (pip)" }
}

# --- Optional capability deps: best effort, never fatal ---
Write-Host "Installing optional dependencies (best effort)..." -ForegroundColor Yellow
if ($useUv) { uv pip install -r requirements-optional.txt --python "$venvPy" }
else { & "$venvPy" -m pip install -r requirements-optional.txt }
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Some optional dependencies failed to install. The agent degrades gracefully (mocks/fallbacks)."
}

# --- Frontend deps (pnpm preferred, npm fallback) ---
Push-Location -LiteralPath (Join-Path $root "apps\web")
try {
    if (Get-Command pnpm -ErrorAction SilentlyContinue) { pnpm install }
    else { npm install }
    $feOk = ($LASTEXITCODE -eq 0)
    if (-not $feOk -and (Test-Path -LiteralPath "node_modules\next")) {
        # pnpm exits non-zero for ignored build scripts even when packages installed fine
        Write-Warning "Package manager reported warnings (e.g. ignored build scripts) but packages are present; continuing."
        $feOk = $true
    }
    if (-not $feOk) { Write-Error "Frontend dependency install failed" }
} finally { Pop-Location }

# --- Runtime directories ---
foreach ($dir in @("data\chroma", "workspace", "logs")) {
    $p = Join-Path $root $dir
    if (-not (Test-Path -LiteralPath $p)) { New-Item -ItemType Directory -Path $p -Force | Out-Null }
}

# --- .env ---
$envFile = Join-Path $root ".env"
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $root ".env.example") -Destination $envFile
    Write-Host "Created .env from .env.example (add API keys there for live providers)."
}

# --- database migrations (Phase 2) ---
Write-Host "Applying database migrations..." -ForegroundColor Cyan
Set-JarvisPythonPath
& "$venvPy" -m jarvis_shared.migrate up
if ($LASTEXITCODE -ne 0) { Write-Error "Migrations failed" }

# --- optional Redis check ---
$redisUp = $false
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect("127.0.0.1", 6379)
    $redisUp = $tcp.Connected
    $tcp.Close()
} catch { $redisUp = $false }
if ($redisUp) {
    Write-Host "Redis detected on 127.0.0.1:6379 (durable queue backend available)." -ForegroundColor Green
} else {
    Write-Warning "Redis not reachable on 127.0.0.1:6379 - the queue will use the SQLite fallback driver (fine for local dev)."
}

Write-Host ""
Write-Host "== Setup complete ==" -ForegroundColor Green
Write-Host "Next steps:"
Write-Host "  1. (Optional) add API keys to .env - everything also works offline with mocks"
Write-Host "  2. Start API:      .\scripts\dev-api.ps1"
Write-Host "  3. Start web UI:   .\scripts\dev-web.ps1"
Write-Host "  4. Desktop bridge: .\scripts\dev-desktop.ps1"
Write-Host "  5. Everything:     .\scripts\dev-all.ps1"
Write-Host "  6. Run tests:      .\scripts\test.ps1"
