# Launch API + web + desktop runtime in separate PowerShell windows.
. (Join-Path $PSScriptRoot "_common.ps1")
$root = Get-JarvisRoot

$shell = if (Get-Command pwsh -ErrorAction SilentlyContinue) { "pwsh" } else { "powershell" }
$procs = @()

# API runs the durable-queue worker in-process (QUEUE_AUTOSTART), so no separate
# worker service is needed here.
foreach ($svc in @(
    @{ Name = "API";     Script = "dev-api.ps1" },
    @{ Name = "Web";     Script = "dev-web.ps1" },
    @{ Name = "Desktop"; Script = "dev-desktop.ps1" }
)) {
    $scriptPath = Join-Path $PSScriptRoot $svc.Script
    $p = Start-Process -FilePath $shell -ArgumentList @("-NoExit", "-ExecutionPolicy", "Bypass", "-File", "`"$scriptPath`"") -PassThru
    $procs += [pscustomobject]@{ Service = $svc.Name; PID = $p.Id }
    Write-Host ("Started {0,-8} PID {1}" -f $svc.Name, $p.Id) -ForegroundColor Green
    Start-Sleep -Seconds 1
}

Write-Host ""
Write-Host "All services launched. To stop them:" -ForegroundColor Cyan
foreach ($p in $procs) { Write-Host ("  Stop-Process -Id {0}   # {1}" -f $p.PID, $p.Service) }
Write-Host "  (or press Ctrl+C / close each window)"
