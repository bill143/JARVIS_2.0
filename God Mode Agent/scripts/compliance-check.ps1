# Compliance verification: migrations applied, audit chain intact, compliance suite.
. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot
Set-Location -LiteralPath $root
Set-JarvisPythonPath
$py = Get-VenvPython

$failed = $false

Write-Host "== Migration status ==" -ForegroundColor Cyan
& "$py" -m jarvis_shared.migrate status
if ($LASTEXITCODE -ne 0) { $failed = $true }

Write-Host "== Audit chain validation (tamper-evidence) ==" -ForegroundColor Cyan
& "$py" (Join-Path $PSScriptRoot "_pyrun.py") audit-verify
if ($LASTEXITCODE -ne 0) { $failed = $true }

Write-Host "== Compliance test suite ==" -ForegroundColor Cyan
& "$py" -m pytest "tests\compliance" -q -p no:warnings
if ($LASTEXITCODE -ne 0) { $failed = $true }

if ($failed) {
    Write-Host "COMPLIANCE CHECK FAILED" -ForegroundColor Red
    exit 1
}
Write-Host "COMPLIANCE CHECK PASSED" -ForegroundColor Green
exit 0
