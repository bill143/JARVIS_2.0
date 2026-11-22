# Run the desktop runtime (camera/mic bridge). Pass -Once for a single frame.
param([switch]$Once)

. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot
Set-Location -LiteralPath $root
Set-JarvisPythonPath
$py = Get-VenvPython

$argsList = @("-m", "jarvis_desktop")
if ($Once) { $argsList += "--once" }

Write-Host "Starting JARVIS desktop runtime (Ctrl+C to stop)" -ForegroundColor Cyan
& "$py" @argsList
exit $LASTEXITCODE
