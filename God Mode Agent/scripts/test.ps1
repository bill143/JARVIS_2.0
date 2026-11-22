# Run unit, integration, and e2e suites. Exits non-zero on any failure.
. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot
Set-Location -LiteralPath $root
Set-JarvisPythonPath
$py = Get-VenvPython

# Ensure migrations are applied before the integration/e2e suites run.
& "$py" -m jarvis_shared.migrate up | Out-Null

$failed = 0
foreach ($suite in @("unit", "integration", "e2e", "security", "evals", "compliance")) {
    Write-Host ""
    Write-Host "== Running $suite tests ==" -ForegroundColor Cyan
    & "$py" -m pytest "tests\$suite" -q -p no:warnings
    if ($LASTEXITCODE -ne 0) { $failed = 1 }
}

if ($failed -ne 0) {
    Write-Host ""
    Write-Host "TESTS FAILED" -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host "ALL TEST SUITES PASSED" -ForegroundColor Green
exit 0
