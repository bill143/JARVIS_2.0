# Shared helpers dot-sourced by every script. Handles the space in the repo path.
$ErrorActionPreference = "Stop"

$script:JarvisRoot = Split-Path -Parent $PSScriptRoot

function Get-JarvisRoot { return $script:JarvisRoot }

function Get-VenvPython {
    $py = Join-Path $script:JarvisRoot ".venv\Scripts\python.exe"
    if (-not (Test-Path -LiteralPath $py)) {
        Write-Error "Virtual env not found at '$py'. Run scripts\setup.ps1 first."
    }
    return $py
}

function Set-JarvisPythonPath {
    $parts = @(
        "packages\agent-core", "packages\model-adapters", "packages\vision",
        "packages\voice", "packages\memory", "packages\tools", "packages\shared",
        "packages\auth", "packages\safety", "packages\policy",
        "packages\reliability", "packages\observability",
        "packages\planner", "packages\agents", "packages\rag",
        "packages\routing", "packages\compliance", "packages\evals",
        "apps\api", "apps\desktop-runtime"
    ) | ForEach-Object { Join-Path $script:JarvisRoot $_ }
    $env:PYTHONPATH = $parts -join ";"
}

function Get-DotEnvValue {
    param([string]$Key, [string]$Default = "")
    $envFile = Join-Path $script:JarvisRoot ".env"
    if (Test-Path -LiteralPath $envFile) {
        foreach ($line in Get-Content -LiteralPath $envFile) {
            if ($line -match "^\s*$([regex]::Escape($Key))\s*=\s*(.*)\s*$") {
                $value = $Matches[1].Trim()
                if ($value -ne "") { return $value }
            }
        }
    }
    return $Default
}
