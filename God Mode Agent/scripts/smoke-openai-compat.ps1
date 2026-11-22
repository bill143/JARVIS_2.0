# Smoke-test the OpenAI-compatible shim against a running God Mode API.
#
# Usage:
#   .\scripts\smoke-openai-compat.ps1 [-BaseUrl http://127.0.0.1:8000] [-ApiKey <jk_...>]
#
# With no -ApiKey it logs in as the seeded admin (admin/admin123) to get a token.
param(
    [string]$BaseUrl = "http://127.0.0.1:8000",
    [string]$ApiKey = ""
)
$ErrorActionPreference = "Stop"
function Section($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }

Section "1) Health"
$health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/health"
$health | ConvertTo-Json -Depth 6

if ($ApiKey) {
    $bearer = $ApiKey
    Write-Host "Using provided API key as Bearer." -ForegroundColor DarkGray
} else {
    Section "2) Auth (admin login)"
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/login" -ContentType "application/json" `
        -Body (@{ username = "admin"; password = "admin123" } | ConvertTo-Json)
    $bearer = $login.data.access_token
    Write-Host "Got admin access token." -ForegroundColor DarkGray
}
$headers = @{ Authorization = "Bearer $bearer" }

Section "3) GET /v1/models"
$models = Invoke-RestMethod -Method Get -Uri "$BaseUrl/v1/models" -Headers $headers
$models | ConvertTo-Json -Depth 6

Section "4) POST /v1/chat/completions"
$payload = @{
    model    = "god-mode-agent"
    messages = @(
        @{ role = "system"; content = "You are JARVIS." },
        @{ role = "user";   content = "Reply with a short hello and say you are online." }
    )
} | ConvertTo-Json -Depth 6
$resp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/v1/chat/completions" -Headers $headers `
    -ContentType "application/json" -Body $payload
$resp | ConvertTo-Json -Depth 8

$content = $resp.choices[0].message.content
if ([string]::IsNullOrWhiteSpace($content)) {
    Write-Host "`nSMOKE FAIL: empty assistant content" -ForegroundColor Red
    exit 1
}
Write-Host "`nSMOKE PASS -- assistant replied:" -ForegroundColor Green
Write-Host $content -ForegroundColor Green
exit 0
