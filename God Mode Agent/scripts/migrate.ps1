# Apply (or roll back) database migrations.
#   .\scripts\migrate.ps1                 # migrate up to latest
#   .\scripts\migrate.ps1 -Command status # show migration status
#   .\scripts\migrate.ps1 -Command down -To 3
param(
    [ValidateSet("up", "down", "status")]
    [string]$Command = "up",
    [int]$To = -1
)

. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot
Set-Location -LiteralPath $root
Set-JarvisPythonPath
$py = Get-VenvPython

$argsList = @("-m", "jarvis_shared.migrate", $Command)
if ($To -ge 0) { $argsList += @("--to", "$To") }

Write-Host "Running migrations: $Command $(if ($To -ge 0) {"--to $To"})" -ForegroundColor Cyan
& "$py" @argsList
exit $LASTEXITCODE
