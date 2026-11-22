# Start the FastAPI backend with auto-reload.
. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot
Set-Location -LiteralPath $root
Set-JarvisPythonPath
$py = Get-VenvPython

$apiHost = Get-DotEnvValue -Key "API_HOST" -Default "127.0.0.1"
$apiPort = Get-DotEnvValue -Key "API_PORT" -Default "8000"

Write-Host "Starting JARVIS API on http://${apiHost}:${apiPort} (Ctrl+C to stop)" -ForegroundColor Cyan
& "$py" -m uvicorn jarvis_api.main:app --host $apiHost --port $apiPort --reload
exit $LASTEXITCODE
