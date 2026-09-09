# Run the security + evals suites (injection, authz, policy, rate-limit, isolation).
. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot
Set-Location -LiteralPath $root
Set-JarvisPythonPath
$py = Get-VenvPython

$failed = 0
foreach ($suite in @("security", "evals")) {
    Write-Host ""
    Write-Host "== Running $suite suite ==" -ForegroundColor Cyan
    & "$py" -m pytest "tests\$suite" -q -p no:warnings
    if ($LASTEXITCODE -ne 0) { $failed = 1 }
}

if ($failed -ne 0) {
    Write-Host ""
    Write-Host "SECURITY/EVALS SUITE FAILED" -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host "SECURITY + EVALS PASSED" -ForegroundColor Green
exit 0
