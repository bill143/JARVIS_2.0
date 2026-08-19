# Run the evaluation platform suites (quality gates for release).
#   .\scripts\evals.ps1            # run the pytest eval suites
#   .\scripts\evals.ps1 -Gate      # also run the full offline eval gate and fail on regression
param([switch]$Gate)

. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot
Set-Location -LiteralPath $root
Set-JarvisPythonPath
$py = Get-VenvPython

Write-Host "== Eval suites ==" -ForegroundColor Cyan
& "$py" -m pytest "tests\evals" -q -p no:warnings
$failed = ($LASTEXITCODE -ne 0)

if ($Gate) {
    Write-Host "== Offline eval gate ==" -ForegroundColor Cyan
    & "$py" (Join-Path $PSScriptRoot "_pyrun.py") eval-gate
    if ($LASTEXITCODE -ne 0) { $failed = $true }
}

if ($failed) {
    Write-Host "EVALS FAILED (quality gate not met)" -ForegroundColor Red
    exit 1
}
Write-Host "EVALS PASSED" -ForegroundColor Green
exit 0
