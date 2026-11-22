# Export an admin evidence package (audit chain + verification) to EVIDENCE_EXPORT_DIR.
#   .\scripts\export-evidence.ps1              # JSON export
#   .\scripts\export-evidence.ps1 -Format csv  # CSV export
param([ValidateSet("json", "csv")][string]$Format = "json")

. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot
Set-Location -LiteralPath $root
Set-JarvisPythonPath
$py = Get-VenvPython

Write-Host "== Exporting compliance evidence ($Format) ==" -ForegroundColor Cyan
& "$py" (Join-Path $PSScriptRoot "_pyrun.py") export-evidence $Format
if ($LASTEXITCODE -ne 0) {
    Write-Host "EVIDENCE EXPORT FAILED" -ForegroundColor Red
    exit 1
}
Write-Host "EVIDENCE EXPORT COMPLETE" -ForegroundColor Green
exit 0
