# Python formatter (ruff format + import sort) + frontend formatter.
. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot
Set-Location -LiteralPath $root
$py = Get-VenvPython

Write-Host "== ruff format ==" -ForegroundColor Cyan
& "$py" -m ruff format packages apps tests
& "$py" -m ruff check --select I --fix packages apps tests

$webDir = Join-Path $root "apps\web"
if (Test-Path -LiteralPath (Join-Path $webDir "node_modules")) {
    Push-Location -LiteralPath $webDir
    try {
        Write-Host "== web format (eslint --fix) ==" -ForegroundColor Cyan
        if (Get-Command pnpm -ErrorAction SilentlyContinue) { pnpm lint --fix } else { npm run lint -- --fix }
    } finally { Pop-Location }
}

Write-Host "Formatting complete." -ForegroundColor Green
