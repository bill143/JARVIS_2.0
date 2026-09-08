# ECHO Command nightly backup (Stage 4).
# Zips configs/ (voices.yaml, agents.yaml, knowledge/) + consistent SQLite
# copies of activity-log.db and jarvis.db into backups\, keeping the last 14.
# Secrets are excluded: any .env*, *secret*, *.key file never enters the zip.
$ErrorActionPreference = 'Stop'
$root = 'C:\dev\JARVIS_2.0'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dest = Join-Path $root 'backups'
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$staging = Join-Path $env:TEMP "echo-backup-$stamp"
New-Item -ItemType Directory -Force -Path $staging | Out-Null

try {
    # configs/ minus anything secret-shaped
    Copy-Item -Recurse -Path (Join-Path $root 'configs') -Destination (Join-Path $staging 'configs')
    Get-ChildItem -Recurse -Force -File (Join-Path $staging 'configs') |
        Where-Object { $_.Name -like '.env*' -or $_.Name -like '*secret*' -or $_.Name -like '*.key' } |
        Remove-Item -Force

    # databases via the SQLite backup API (consistent even in WAL mode)
    $py = Join-Path $root 'God Mode Agent\.venv\Scripts\python.exe'
    $dataDir = Join-Path $staging 'data'
    New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
    & $py (Join-Path $root 'scripts\sqlite_backup.py') `
        (Join-Path $root 'God Mode Agent\data\activity-log.db') (Join-Path $dataDir 'activity-log.db')
    if ($LASTEXITCODE -ne 0) { throw 'activity-log.db backup failed' }
    & $py (Join-Path $root 'scripts\sqlite_backup.py') `
        (Join-Path $root 'God Mode Agent\jarvis.db') (Join-Path $dataDir 'jarvis.db')
    if ($LASTEXITCODE -ne 0) { throw 'jarvis.db backup failed' }

    $zip = Join-Path $dest "echo-backup-$stamp.zip"
    Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zip
    Write-Output "backup written: $zip"
} finally {
    Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
}

# prune to the newest 14
Get-ChildItem $dest -Filter 'echo-backup-*.zip' |
    Sort-Object Name -Descending | Select-Object -Skip 14 | Remove-Item -Force
