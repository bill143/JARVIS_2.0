# Python lint (ruff) + frontend lint (next lint).
. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot
Set-Location -LiteralPath $root
$py = Get-VenvPython

$failed = 0

Write-Host "== ruff check ==" -ForegroundColor Cyan
& "$py" -m ruff check packages apps tests
if ($LASTEXITCODE -ne 0) { $failed = 1 }

$webDir = Join-Path $root "apps\web"
if (Test-Path -LiteralPath (Join-Path $webDir "node_modules")) {
    Write-Host "== web lint ==" -ForegroundColor Cyan
    Push-Location -LiteralPath $webDir
    try {
        if (Get-Command pnpm -ErrorAction SilentlyContinue) { pnpm lint } else { npm run lint }
        if ($LASTEXITCODE -ne 0) { $failed = 1 }
    } finally { Pop-Location }
} else {
    Write-Warning "apps\web\node_modules missing - run scripts\setup.ps1 first; skipping web lint."
}

exit $failed
