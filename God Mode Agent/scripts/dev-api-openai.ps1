# Start the FastAPI backend WITH the OpenAI-compatible shim (/v1/*) and auto-reload.
# Identical to dev-api.ps1 but serves jarvis_api.serve_openai:app, so external
# OpenAI-style clients (e.g. the Java JARVIS brain) can call /v1/chat/completions.
. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot
Set-Location -LiteralPath $root
Set-JarvisPythonPath
$py = Get-VenvPython

$apiHost = Get-DotEnvValue -Key "API_HOST" -Default "127.0.0.1"
$apiPort = Get-DotEnvValue -Key "API_PORT" -Default "8000"

Write-Host "Starting JARVIS API (+OpenAI /v1 shim) on http://${apiHost}:${apiPort} (Ctrl+C to stop)" -ForegroundColor Cyan
Write-Host "  Chat:   POST http://${apiHost}:${apiPort}/v1/chat/completions" -ForegroundColor DarkCyan
Write-Host "  Models: GET  http://${apiHost}:${apiPort}/v1/models" -ForegroundColor DarkCyan
& "$py" -m uvicorn jarvis_api.serve_openai:app --host $apiHost --port $apiPort --reload
exit $LASTEXITCODE
