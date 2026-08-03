# ============================================================
#  JARVIS one-click updater
#  Right-click this file > "Run with PowerShell"  (or run .\update-jarvis.ps1)
#
#  What it does, in order:
#   1. Backs up ANY local changes to a timestamped git branch
#      (pushed to GitHub when possible) - nothing is ever lost.
#   2. Pulls the latest integration/all-features.
#   3. Rebuilds the app.
#   4. Stops any old instance on port 8080 and starts the new one,
#      then opens the dashboard in your browser.
# ============================================================
$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

$stamp  = Get-Date -Format "yyyyMMdd-HHmmss"
$target = "integration/all-features"

Write-Host ""
Write-Host "== J.A.R.V.I.S. updater ==" -ForegroundColor Cyan

# -- 1. Preserve local work ---------------------------------------------------
# SECURITY: this backup branch gets pushed to GitHub, so files that may hold real secrets are
# never swept into it, no matter how dirty the tree is. Enforced two ways — pathspec exclusion on
# the add itself, plus an explicit unstage-and-warn safety net in case anything slips through a
# pathspec-matching edge case (e.g. a nested path). A 2026-07-20 incident (a real TOGETHER_API_KEY
# + SENTRY_DSN pasted into .env.example, then swept up and pushed by this exact routine) is why
# both layers exist — don't remove either without replacing it with something at least as strict.
$secretPatterns = @('.env', '.env.example', '.env.local', '.env.*', 'credentials.toml',
    '*api*key*.txt', '*.pem', '*.key')

$dirty = git status --porcelain
if ($dirty) {
    $backup = "local-backup-$stamp"
    Write-Host "Local changes detected - backing them up to branch '$backup'..." -ForegroundColor Yellow
    git checkout -b $backup | Out-Null

    $excludePathspecs = $secretPatterns | ForEach-Object { ":!$_" }
    git add -A -- . @excludePathspecs

    # Safety net: unstage anything that still matches, and warn loudly rather than staying silent.
    $staged = git diff --cached --name-only
    foreach ($file in $staged) {
        $leaf = Split-Path $file -Leaf
        if ($secretPatterns | Where-Object { $leaf -like $_ }) {
            Write-Host "WARNING: '$file' matched a secrets pattern - unstaged, NOT backed up." -ForegroundColor Red
            git reset -- $file | Out-Null
        }
    }

    git commit -m "backup: local machine changes before update ($stamp)" | Out-Null
    try {
        git push -u origin $backup 2>$null | Out-Null
        Write-Host "Backup branch pushed to GitHub: $backup" -ForegroundColor Green
    } catch {
        Write-Host "Backup branch saved locally as '$backup' (push skipped)." -ForegroundColor Yellow
    }
}

# -- 2. Update ------------------------------------------------------------------
git checkout $target
git pull origin $target

# -- 3. Build ---------------------------------------------------------------------
Set-Location -Path (Join-Path $PSScriptRoot "java")
Write-Host "Building (this can take a few minutes)..." -ForegroundColor Cyan
.\mvnw.cmd -q package
if ($LASTEXITCODE -ne 0) { throw "Build failed - see the output above." }

# -- 4. (Re)start ---------------------------------------------------------------
$listeners = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
foreach ($c in $listeners) {
    Write-Host "Stopping old instance (pid $($c.OwningProcess))..." -ForegroundColor Yellow
    Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
}
Start-Process java -ArgumentList '-jar','app\target\jarvis.jar' `
    -WorkingDirectory (Join-Path $PSScriptRoot "java")
Start-Sleep -Seconds 4
Start-Process "http://localhost:8080"

Write-Host ""
Write-Host "J.A.R.V.I.S. is up:  http://localhost:8080" -ForegroundColor Green
Write-Host "(You can close this window - JARVIS keeps running in its own process.)"
Read-Host "Press Enter to close"
