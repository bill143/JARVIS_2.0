# ECHO + JARVIS Bootstrap -- Phase 0
# Autonomous overnight execution. Operator: Bill Asmar. Date: 2026-04-18.
# Stops at end of Step 7. Does not begin Phase 1.

$ErrorActionPreference = 'Continue'

# ---- Constants ----
$PARENT          = "C:\Users\Bill Asmar\OneDrive - ONeill Contractors, Inc\JARVIS & ECHOS NEW REPO"
$JARVIS_DIR      = "$PARENT\JARVIS NEW REPO"
$ECHO_DIR        = "$PARENT\ECHO NEW REPO"
$JARVIS_JUNCTION = "C:\dev\jarvis"
$ECHO_JUNCTION   = "C:\dev\echo"
$LOG             = "$PARENT\bootstrap-errors.log"
$REPORT          = "$PARENT\bootstrap-report.md"

$JARVIS_REPO_URL    = "https://github.com/bill143/JARVIS_2.0.git"
$JARVIS_UPSTREAM    = "https://github.com/open-jarvis/OpenJarvis.git"
$ECHO_REPO_URL      = "https://github.com/bill143/JARVIS-ECHO-AI.git"

# ---- State tracker ----
$State = @{
    PreFlight      = @{}
    Folders        = @{}
    Pinned         = @{}
    Junctions      = @{ Jarvis = $null; Echo = $null }
    Clone          = @{ Jarvis = $null; Echo = $null }
    GitHidden      = @{}
    EchoScaffold   = @{ Branch = $null; Staged = @() }
    MorningReview  = @{
        OneDriveExclusions          = ""
        ToolsToInstall              = @()
        GitIdentitySubstituted      = $false
        JunctionsNeedManualCreation = $false
        AuthFailures                = @()
        ConflictDetections          = @()
    }
    Steps          = [ordered]@{}
    HardStopFired  = $false
    OneDrivePaused = $false
}

# ---- Helpers ----
function Log-Entry {
    param([string]$Message)
    $ts = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
    try {
        Add-Content -LiteralPath $LOG -Value "[$ts] $Message" -ErrorAction Stop
    } catch {
        # fallback: write to console
        Write-Host "[$ts] (log-fail) $Message"
    }
}

function Redact-Secrets {
    param([string]$Text)
    if (-not $Text) { return "" }
    return ($Text -replace '(gh[ps]_[A-Za-z0-9]+)', '[REDACTED]' `
                  -replace '(x-access-token:[^@]+)', '[REDACTED]' `
                  -replace '([A-Za-z0-9/+]{40,}=*)', '[REDACTED-LONG]')
}

function Truncate-Text {
    param([string]$Text, [int]$Max = 500)
    if (-not $Text) { return "" }
    if ($Text.Length -le $Max) { return $Text }
    return $Text.Substring(0, $Max)
}

function Repo-WorkPath {
    param([string]$Junction, [string]$DirectPath)
    if (Test-Path -LiteralPath $Junction) { return $Junction } else { return $DirectPath }
}

# Initialize/rotate log
if (-not (Test-Path -LiteralPath $PARENT)) {
    Write-Host "FATAL: parent not found: $PARENT"
    exit 1
}
Set-Content -LiteralPath $LOG -Value "" -Encoding UTF8 -ErrorAction SilentlyContinue
Log-Entry "=== Bootstrap run START ==="
Log-Entry "Operator: Bill Asmar | Date: 2026-04-18 | Parent: $PARENT"

# =====================================================================
# Step 0 -- Pre-Flight Checks
# =====================================================================
Log-Entry "Step 0 START"
try {
    # 0.1 Admin
    $isAdmin = $false
    try {
        $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
        $isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)
    } catch { $isAdmin = $false }
    $State.PreFlight["0.1_Admin"] = $isAdmin
    Log-Entry "Step 0.1: Admin=$isAdmin"

    # 0.2 Git
    $gitVer = $null
    try { $gitVer = (& git --version 2>&1) -join " " } catch { $gitVer = $null }
    $State.PreFlight["0.2_Git"] = $gitVer
    Log-Entry "Step 0.2: Git=$gitVer"

    # 0.3 Python
    $pyVer = $null
    try { $pyVer = (& py -3 --version 2>&1) -join " " } catch { $pyVer = $null }
    if (-not $pyVer -or $pyVer -match 'not recognized') {
        try { $pyVer = (& python --version 2>&1) -join " " } catch { $pyVer = $null }
    }
    $State.PreFlight["0.3_Python"] = $pyVer
    Log-Entry "Step 0.3: Python=$pyVer"

    # 0.4 Rust
    $rustVer = $null; $cargoVer = $null
    try { $rustVer = (& rustc --version 2>&1) -join " " } catch { $rustVer = $null }
    try { $cargoVer = (& cargo --version 2>&1) -join " " } catch { $cargoVer = $null }
    $State.PreFlight["0.4_Rust"] = "$rustVer | $cargoVer"
    Log-Entry "Step 0.4: Rust=$rustVer ; Cargo=$cargoVer"

    # 0.5 uv
    $uvVer = $null
    try { $uvVer = (& uv --version 2>&1) -join " " } catch { $uvVer = $null }
    $State.PreFlight["0.5_uv"] = $uvVer
    Log-Entry "Step 0.5: uv=$uvVer"

    # 0.6 Docker
    $dockerVer = $null; $dockerPs = $null
    try { $dockerVer = (& docker --version 2>&1) -join " " } catch { $dockerVer = $null }
    try { $dockerPs = (& docker ps 2>&1) -join " " } catch { $dockerPs = $null }
    $State.PreFlight["0.6_Docker"] = "$dockerVer | ps: $(Truncate-Text $dockerPs 120)"
    Log-Entry "Step 0.6: Docker=$dockerVer"

    # 0.7 Git identity
    $gitName = ""; $gitEmail = ""
    try { $gitName = (& git config --global user.name 2>&1) -join "" } catch { $gitName = "" }
    try { $gitEmail = (& git config --global user.email 2>&1) -join "" } catch { $gitEmail = "" }
    $State.PreFlight["0.7_GitName"]  = $gitName
    $State.PreFlight["0.7_GitEmail"] = $gitEmail
    Log-Entry "Step 0.7: git user.name='$gitName' user.email='$gitEmail'"

    # 0.7b GitHub CLI
    $ghVer = $null; $ghAuth = $null
    try { $ghVer  = (& gh --version 2>&1) -join " " } catch { $ghVer = $null }
    try { $ghAuth = (& gh auth status 2>&1) -join " " } catch { $ghAuth = $null }
    $State.PreFlight["0.7b_gh"]       = $ghVer
    $State.PreFlight["0.7b_gh_auth"]  = Truncate-Text (Redact-Secrets $ghAuth) 200
    Log-Entry "Step 0.7b: gh=$ghVer"

    # 0.8 Ollama
    $ollamaVer = $null
    try { $ollamaVer = (& ollama --version 2>&1) -join " " } catch { $ollamaVer = $null }
    $State.PreFlight["0.8_Ollama"] = $ollamaVer
    Log-Entry "Step 0.8: Ollama=$ollamaVer"

    # Emit table to stdout
    Write-Host ""
    Write-Host "=== Step 0 Pre-Flight Results ==="
    foreach ($k in $State.PreFlight.Keys | Sort-Object) {
        $v = $State.PreFlight[$k]
        if ($null -eq $v -or "$v" -eq "") { $v = "(missing)" }
        Write-Host ("  {0,-20} {1}" -f $k, $v)
    }
    Write-Host ""

    # Apply git identity defaults per Sec7.1
    if (-not $gitName -or $gitName.Trim() -eq "") {
        & git config --global user.name "Bill Asmar" 2>&1 | Out-Null
        $State.MorningReview.GitIdentitySubstituted = $true
        Log-Entry "Step 0 Sec7.1: user.name substituted with 'Bill Asmar'"
    }
    if (-not $gitEmail -or $gitEmail.Trim() -eq "") {
        & git config --global user.email "bill@oneillcontractors.com" 2>&1 | Out-Null
        $State.MorningReview.GitIdentitySubstituted = $true
        Log-Entry "Step 0 Sec7.1: user.email substituted with 'bill@oneillcontractors.com'"
    }

    $State.Steps["Step 0"] = "ok"
    Log-Entry "Step 0 END"
} catch {
    $State.Steps["Step 0"] = "partial: $(Truncate-Text $_.Exception.Message 200)"
    Log-Entry "Step 0 FAILED: $(Redact-Secrets $_.Exception.Message)"
}

# =====================================================================
# Step 1 -- Create Folder Structure
# =====================================================================
Log-Entry "Step 1 START"
try {
    foreach ($p in @($JARVIS_DIR, $ECHO_DIR)) {
        if (Test-Path -LiteralPath $p) {
            Log-Entry "Step 1: $p already exists -- skip create"
            $State.Folders[$p] = "exists"
        } else {
            New-Item -ItemType Directory -Force -Path $p -ErrorAction Stop | Out-Null
            Log-Entry "Step 1: created $p"
            $State.Folders[$p] = "created"
        }
        try { $full = (Get-Item -LiteralPath $p).FullName } catch { $full = $p }
        Log-Entry "Step 1: verified path = $full"
    }
    $State.Steps["Step 1"] = "ok"
    Log-Entry "Step 1 END"
} catch {
    $State.Steps["Step 1"] = "failed: $(Truncate-Text $_.Exception.Message 200)"
    Log-Entry "Step 1 FAILED: $(Redact-Secrets $_.Exception.Message)"
}

# =====================================================================
# Step 2 -- OneDrive Pin + Exclusion Reminder (do NOT pause OneDrive here)
# =====================================================================
Log-Entry "Step 2 START"
try {
    foreach ($p in @($JARVIS_DIR, $ECHO_DIR)) {
        if (-not (Test-Path -LiteralPath $p)) {
            $State.Pinned[$p] = "skipped-missing"
            continue
        }
        try {
            & attrib +P $p /D /S 2>&1 | Out-Null
            Log-Entry "Step 2a: attrib +P applied to $p"
            $State.Pinned[$p] = "ok"
        } catch {
            Log-Entry "Step 2a: attrib +P failed on $p : $(Redact-Secrets $_.Exception.Message) -- continuing"
            $State.Pinned[$p] = "failed"
        }
    }

    $State.MorningReview.OneDriveExclusions = @"
OneDrive GUI -> Settings -> Sync and backup -> Manage backup -> Choose folders
Exclude from sync (add to "Excluded files and folders"):
  - .git
  - target
  - .venv
  - node_modules
  - __pycache__
  - .pytest_cache
"@
    $State.Steps["Step 2"] = "ok"
    Log-Entry "Step 2 END"
} catch {
    $State.Steps["Step 2"] = "partial: $(Truncate-Text $_.Exception.Message 200)"
    Log-Entry "Step 2 FAILED: $(Redact-Secrets $_.Exception.Message)"
}

# =====================================================================
# Step 3 -- Junction Points (Requires Admin)
# =====================================================================
Log-Entry "Step 3 START"

function Ensure-Junction {
    param([string]$LinkPath, [string]$TargetPath)
    if (Test-Path -LiteralPath $LinkPath) {
        try {
            $item = Get-Item -LiteralPath $LinkPath -Force
            $isReparse = ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq [IO.FileAttributes]::ReparsePoint
            if ($isReparse) {
                $existing = $null
                try {
                    if ($item.PSObject.Properties['Target'] -and $item.Target) {
                        $existing = if ($item.Target -is [array]) { $item.Target[0] } else { $item.Target }
                    } elseif ($item.PSObject.Properties['LinkTarget']) {
                        $existing = $item.LinkTarget
                    }
                } catch { $existing = $null }
                if ($existing -and ($existing -ieq $TargetPath)) {
                    Log-Entry "Step 3: $LinkPath already points to $TargetPath -- verified"
                    return @{ Status = "verified"; Target = $existing }
                }
                Log-Entry "Step 3 Sec7.2: $LinkPath points to '$existing' (wanted '$TargetPath') -- skip"
                return @{ Status = "wrong-target"; Target = $existing }
            }
            Log-Entry "Step 3 Sec7.2: $LinkPath exists as non-junction -- skip"
            return @{ Status = "not-a-junction"; Target = $null }
        } catch {
            Log-Entry "Step 3: inspect failed on $LinkPath : $(Redact-Secrets $_.Exception.Message) -- skip"
            return @{ Status = "inspect-failed"; Target = $null }
        }
    }
    try {
        New-Item -ItemType Junction -Path $LinkPath -Target $TargetPath -ErrorAction Stop | Out-Null
        Log-Entry "Step 3: created junction $LinkPath -> $TargetPath"
        return @{ Status = "created"; Target = $TargetPath }
    } catch {
        Log-Entry "Step 3: junction create failed $LinkPath : $(Redact-Secrets $_.Exception.Message)"
        return @{ Status = "create-failed"; Target = $null }
    }
}

try {
    if (-not (Test-Path -LiteralPath "C:\dev")) {
        New-Item -ItemType Directory -Force -Path "C:\dev" | Out-Null
        Log-Entry "Step 3a: created C:\dev"
    }

    if (-not $State.PreFlight["0.1_Admin"]) {
        Log-Entry "Step 3 skipped -- not elevated (Sec6.1)"
        $State.Steps["Step 3"] = "skipped-not-elevated"
        $State.MorningReview.JunctionsNeedManualCreation = $true
        $State.Junctions.Jarvis = @{ Status = "skipped-not-elevated"; Target = $null }
        $State.Junctions.Echo   = @{ Status = "skipped-not-elevated"; Target = $null }
    } else {
        $State.Junctions.Jarvis = Ensure-Junction -LinkPath $JARVIS_JUNCTION -TargetPath $JARVIS_DIR
        $State.Junctions.Echo   = Ensure-Junction -LinkPath $ECHO_JUNCTION   -TargetPath $ECHO_DIR

        # 3d -- list reparse points under C:\dev
        try {
            $rps = Get-ChildItem -LiteralPath "C:\dev" -Force -ErrorAction SilentlyContinue |
                   Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq [IO.FileAttributes]::ReparsePoint }
            foreach ($rp in $rps) {
                $tgt = $null
                try {
                    if ($rp.PSObject.Properties['Target'] -and $rp.Target) {
                        $tgt = if ($rp.Target -is [array]) { $rp.Target[0] } else { $rp.Target }
                    } elseif ($rp.PSObject.Properties['LinkTarget']) {
                        $tgt = $rp.LinkTarget
                    }
                } catch { $tgt = $null }
                Log-Entry "Step 3d: C:\dev\$($rp.Name) -> $tgt"
            }
        } catch { }

        $State.Steps["Step 3"] = "ok"
    }
    Log-Entry "Step 3 END"
} catch {
    $State.Steps["Step 3"] = "partial: $(Truncate-Text $_.Exception.Message 200)"
    Log-Entry "Step 3 FAILED: $(Redact-Secrets $_.Exception.Message)"
}

# =====================================================================
# Step 4 -- Clone Repositories
# =====================================================================
Log-Entry "Step 4 START"

function Has-ConflictFiles {
    param([string]$Dir)
    if (-not (Test-Path -LiteralPath $Dir)) { return @() }
    try {
        return Get-ChildItem -LiteralPath $Dir -Force -Recurse -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -match 'conflict|Conflicted Copy|-\d+-\d+' }
    } catch { return @() }
}

function Clone-Repo {
    param(
        [string]$Name,
        [string]$Url,
        [string]$TargetDir
    )
    $conflicts = Has-ConflictFiles -Dir $TargetDir
    if ($conflicts -and $conflicts.Count -gt 0) {
        $names = ($conflicts | Select-Object -First 5 | ForEach-Object { $_.FullName }) -join "; "
        Log-Entry "Step 4 Sec6.3: conflict files in $TargetDir -- skipping $Name clone. Samples: $(Truncate-Text $names 300)"
        $State.MorningReview.ConflictDetections += "$TargetDir"
        return "skipped-conflicts"
    }
    if (Test-Path -LiteralPath "$TargetDir\.git") {
        Log-Entry "Step 4: $TargetDir already cloned -- skip"
        return "exists"
    }
    $attempt1 = & git clone $Url $TargetDir 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        $red = Redact-Secrets (($attempt1 | Out-String).Trim())
        if ($red -match '(?i)401|403|fatal:\s*Authentication|could not read Username|Authentication failed') {
            Log-Entry "Step 4 Sec6.2: $Name clone auth failure -- skip. stderr: $(Truncate-Text $red 500)"
            $State.MorningReview.AuthFailures += "$Name ($Url)"
            return "skipped-auth"
        }
        Log-Entry "Step 4: $Name clone failed -- retry once. stderr: $(Truncate-Text $red 500)"
        $attempt2 = & git clone $Url $TargetDir 2>&1
        if ($LASTEXITCODE -ne 0) {
            $red2 = Redact-Secrets (($attempt2 | Out-String).Trim())
            Log-Entry "Step 4: $Name clone failed on retry. stderr: $(Truncate-Text $red2 500)"
            if ($red2 -match '(?i)401|403|fatal:\s*Authentication|could not read Username|Authentication failed') {
                $State.MorningReview.AuthFailures += "$Name ($Url)"
                return "skipped-auth"
            }
            return "failed"
        }
        Log-Entry "Step 4: $Name cloned on retry"
        return "cloned"
    }
    Log-Entry "Step 4: $Name cloned"
    return "cloned"
}

try {
    # 4a JARVIS
    $State.Clone.Jarvis = Clone-Repo -Name "JARVIS" -Url $JARVIS_REPO_URL -TargetDir $JARVIS_DIR

    if ($State.Clone.Jarvis -in @("cloned","exists")) {
        Push-Location -LiteralPath $JARVIS_DIR
        try {
            $remotes = (& git remote 2>&1) -split "`r?`n" | Where-Object { $_ -and $_.Trim() }
            if ($remotes -notcontains "upstream") {
                & git remote add upstream $JARVIS_UPSTREAM 2>&1 | Out-Null
                Log-Entry "Step 4: JARVIS upstream remote added ($JARVIS_UPSTREAM)"
            } else {
                Log-Entry "Step 4: JARVIS upstream remote already present"
            }
        } finally { Pop-Location }
    }

    # 4b ECHO
    $State.Clone.Echo = Clone-Repo -Name "ECHO" -Url $ECHO_REPO_URL -TargetDir $ECHO_DIR

    if ($State.Clone.Echo -in @("cloned","exists")) {
        Push-Location -LiteralPath $ECHO_DIR
        try {
            $branch = (& git symbolic-ref --short HEAD 2>$null) -join ""
            if (-not $branch -or $branch.Trim() -eq "") {
                & git checkout -b main 2>&1 | Out-Null
                Log-Entry "Step 4: ECHO initialized on branch main"
            } else {
                Log-Entry "Step 4: ECHO on branch '$branch'"
            }
        } finally { Pop-Location }
    }

    # 4c Pause OneDrive per Sec5.5
    $odPaused = $false
    $od = Get-Process -Name OneDrive -ErrorAction SilentlyContinue
    if ($od) {
        try {
            Stop-Process -Name OneDrive -Force -ErrorAction Stop
            Start-Sleep -Seconds 2
            $odPaused = $true
            $State.OneDrivePaused = $true
            Log-Entry "Step 4c: OneDrive paused"
        } catch {
            Log-Entry "Step 4c: could not pause OneDrive ($(Redact-Secrets $_.Exception.Message)) -- skipping attrib per Sec5.5"
        }
    } else {
        Log-Entry "Step 4c: OneDrive process not running -- skipping attrib per Sec5.5"
    }

    # 4d attrib +H +S on .git (only if paused)
    if ($odPaused) {
        foreach ($g in @("$JARVIS_DIR\.git", "$ECHO_DIR\.git")) {
            if (Test-Path -LiteralPath $g) {
                try {
                    & attrib +H +S $g /D /S 2>&1 | Out-Null
                    Log-Entry "Step 4d: attrib +H +S applied to $g"
                    $State.GitHidden[$g] = "applied"
                } catch {
                    Log-Entry "Step 4d: attrib failed on $g : $(Redact-Secrets $_.Exception.Message)"
                    $State.GitHidden[$g] = "failed"
                }
            } else {
                $State.GitHidden[$g] = "absent"
            }
        }
    } else {
        $State.GitHidden["Status"] = "skipped-onedrive-not-paused"
        Log-Entry "Step 4d: skipped per Sec5.5 (OneDrive not paused)"
    }

    # 4e Restart OneDrive
    if ($odPaused) {
        $odExe = "$env:LOCALAPPDATA\Microsoft\OneDrive\OneDrive.exe"
        if (Test-Path -LiteralPath $odExe) {
            try {
                Start-Process -FilePath $odExe
                Log-Entry "Step 4e: OneDrive restarted ($odExe)"
            } catch {
                Log-Entry "Step 4e: OneDrive restart failed: $(Redact-Secrets $_.Exception.Message)"
            }
        } else {
            Log-Entry "Step 4e: OneDrive exe not found at $odExe -- cannot restart"
        }
    }

    # 4f End-to-end verify
    $jarvisWork = Repo-WorkPath -Junction $JARVIS_JUNCTION -DirectPath $JARVIS_DIR
    $echoWork   = Repo-WorkPath -Junction $ECHO_JUNCTION   -DirectPath $ECHO_DIR
    foreach ($w in @($jarvisWork, $echoWork)) {
        if (Test-Path -LiteralPath "$w\.git") {
            Push-Location -LiteralPath $w
            try {
                $s = (& git status --short 2>&1 | Out-String).Trim()
                Log-Entry "Step 4f: git status via '$w' => '$(Truncate-Text $s 200)'"
            } finally { Pop-Location }
        } else {
            Log-Entry "Step 4f: no .git at '$w' -- skip verify"
        }
    }

    $state4 = "ok"
    if ($State.Clone.Jarvis -notin @("cloned","exists")) { $state4 = "partial: JARVIS=$($State.Clone.Jarvis)" }
    if ($State.Clone.Echo   -notin @("cloned","exists")) { $state4 = "$state4 ; ECHO=$($State.Clone.Echo)" }
    $State.Steps["Step 4"] = $state4
    Log-Entry "Step 4 END ($state4)"
} catch {
    $State.Steps["Step 4"] = "failed: $(Truncate-Text $_.Exception.Message 200)"
    Log-Entry "Step 4 FAILED: $(Redact-Secrets $_.Exception.Message)"
}

# =====================================================================
# Step 5 -- ECHO Scaffold (branch + stage, no commit)
# =====================================================================
Log-Entry "Step 5 START"
try {
    if ($State.Clone.Echo -notin @("cloned","exists")) {
        Log-Entry "Step 5: skipped -- ECHO clone state is '$($State.Clone.Echo)'"
        $State.Steps["Step 5"] = "skipped-no-echo-clone"
    } else {
        $echoWork = Repo-WorkPath -Junction $ECHO_JUNCTION -DirectPath $ECHO_DIR
        Push-Location -LiteralPath $echoWork
        try {
            # 5a branch
            $current = ((& git symbolic-ref --short HEAD 2>$null) -join "").Trim()
            if ($current -ne "phase-0/scaffold") {
                $exists = (& git rev-parse --verify --quiet "refs/heads/phase-0/scaffold" 2>&1 | Out-String).Trim()
                if ($LASTEXITCODE -eq 0 -and $exists) {
                    & git checkout phase-0/scaffold 2>&1 | Out-Null
                    Log-Entry "Step 5a: checked out existing phase-0/scaffold"
                } else {
                    & git checkout -b phase-0/scaffold 2>&1 | Out-Null
                    Log-Entry "Step 5a: created + checked out phase-0/scaffold"
                }
            } else {
                Log-Entry "Step 5a: already on phase-0/scaffold"
            }

            # 5b .gitignore
            $gitignoreContent = @"
# Python
__pycache__/
*.py[cod]
*`$py.class
.venv/
venv/
env/
*.egg-info/
*.egg
pip-log.txt
pip-delete-this-directory.txt

# Secrets
.env
.env.*
!.env.example
*.key
*.pem
credentials.json
secrets.*

# Docker / Railway
*.pid
docker-compose.override.yml
.railway/

# LiteLLM runtime
litellm_uuid.txt
litellm_cache/

# Node
node_modules/
dist/
build/
.next/

# Test / coverage
.pytest_cache/
.coverage
htmlcov/
.tox/

# Logs / OS / IDE
*.log
.DS_Store
Thumbs.db
.vscode/
.idea/
"@
            $gi = Join-Path $echoWork ".gitignore"
            if (-not (Test-Path -LiteralPath $gi)) {
                Set-Content -LiteralPath $gi -Value $gitignoreContent -Encoding UTF8
                Log-Entry "Step 5b: wrote .gitignore"
            } else {
                Log-Entry "Step 5b: .gitignore exists -- skip"
            }

            # 5c README.md
            $rm = Join-Path $echoWork "README.md"
            if (-not (Test-Path -LiteralPath $rm)) {
                Set-Content -LiteralPath $rm -Value "ECHO -- LiteLLM proxy for the JARVIS stack. Under construction." -Encoding UTF8
                Log-Entry "Step 5c: wrote README.md"
            } else {
                Log-Entry "Step 5c: README.md exists -- skip"
            }

            # 5d .env.example
            $ee = Join-Path $echoWork ".env.example"
            if (-not (Test-Path -LiteralPath $ee)) {
                Set-Content -LiteralPath $ee -Value "# ECHO environment variables -- populate in .env (never commit). Keys TBD in Phase 1." -Encoding UTF8
                Log-Entry "Step 5d: wrote .env.example"
            } else {
                Log-Entry "Step 5d: .env.example exists -- skip"
            }

            # 5e stage only
            & git add .gitignore README.md .env.example 2>&1 | Out-Null
            Log-Entry "Step 5e: staged .gitignore, README.md, .env.example on phase-0/scaffold (no commit)"
            $State.EchoScaffold.Branch = "phase-0/scaffold"
            $State.EchoScaffold.Staged = @(".gitignore", "README.md", ".env.example")
            $State.Steps["Step 5"] = "ok"
        } finally { Pop-Location }
    }
    Log-Entry "Step 5 END"
} catch {
    $State.Steps["Step 5"] = "failed: $(Truncate-Text $_.Exception.Message 200)"
    Log-Entry "Step 5 FAILED: $(Redact-Secrets $_.Exception.Message)"
}

# =====================================================================
# Step 6 -- Missing-Tool Enumeration (no install)
# =====================================================================
Log-Entry "Step 6 START"
try {
    $tools = @()
    if (-not $State.PreFlight["0.7b_gh"]) {
        $tools += "GitHub CLI :: winget install GitHub.cli"
    }
    if (-not $State.PreFlight["0.5_uv"]) {
        $tools += "uv :: winget install astral-sh.uv  (fallback: pip install uv)"
    }
    $rustInfo = "$($State.PreFlight['0.4_Rust'])"
    if ($rustInfo -match 'not recognized' -or $rustInfo -match '^\s*\|\s*$' -or -not $rustInfo.Trim() -or $rustInfo.Trim() -eq '|') {
        $tools += "Rust toolchain :: winget install Rustlang.Rustup  (then: rustup default stable)"
    }
    if (-not $State.PreFlight["0.8_Ollama"]) {
        $tools += "Ollama :: manual download from https://ollama.com/download"
    }
    $dockerInfo = "$($State.PreFlight['0.6_Docker'])"
    if (-not $dockerInfo -or $dockerInfo -match 'not recognized' -or $dockerInfo -match 'cannot connect|error during connect|pipe.*docker_engine') {
        $tools += "Docker Desktop :: winget install Docker.DockerDesktop  (or start Docker Desktop if installed)"
    }
    $State.MorningReview.ToolsToInstall = $tools
    foreach ($t in $tools) { Log-Entry "Step 6: missing -> $t" }
    $State.Steps["Step 6"] = "ok"
    Log-Entry "Step 6 END"
} catch {
    $State.Steps["Step 6"] = "failed: $(Truncate-Text $_.Exception.Message 200)"
    Log-Entry "Step 6 FAILED: $(Redact-Secrets $_.Exception.Message)"
}

# =====================================================================
# Step 7 -- Verification & Report
# =====================================================================
Log-Entry "Step 7 START"
try {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("# ECHO + JARVIS Bootstrap -- Phase 0 Report")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("- **Operator**: Bill Asmar")
    [void]$sb.AppendLine("- **Date**: 2026-04-18")
    [void]$sb.AppendLine("- **Parent**: $PARENT")
    [void]$sb.AppendLine("- **Log**: bootstrap-errors.log")
    [void]$sb.AppendLine("")

    # Section A
    [void]$sb.AppendLine("## Section A -- Step Status")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("| Step | Description | Status | Notes |")
    [void]$sb.AppendLine("|------|-------------|--------|-------|")
    $descMap = @{
        "Step 0" = "Pre-flight tool / env checks"
        "Step 1" = "Create folder structure"
        "Step 2" = "OneDrive pin + exclusion reminder"
        "Step 3" = "Junction points under C:\dev"
        "Step 4" = "Clone JARVIS + ECHO repos"
        "Step 5" = "ECHO scaffold (branch + stage)"
        "Step 6" = "Missing-tool enumeration"
        "Step 7" = "Report generation"
    }
    foreach ($k in @("Step 0","Step 1","Step 2","Step 3","Step 4","Step 5","Step 6","Step 7")) {
        $v = $State.Steps[$k]
        if (-not $v) { $v = "pending" }
        $row = "| $k | $($descMap[$k]) | $v |  |"
        [void]$sb.AppendLine($row)
    }
    [void]$sb.AppendLine("")

    # Section B
    [void]$sb.AppendLine("## Section B -- Verification Details")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("### B.1 Parent & Folders")
    [void]$sb.AppendLine("- Parent exists: $(Test-Path -LiteralPath $PARENT)")
    foreach ($k in $State.Folders.Keys) {
        [void]$sb.AppendLine("- $k => $($State.Folders[$k])")
    }
    [void]$sb.AppendLine("")

    [void]$sb.AppendLine("### B.2 Junctions (C:\dev)")
    foreach ($pair in @(@('Jarvis',$JARVIS_JUNCTION,$JARVIS_DIR), @('Echo',$ECHO_JUNCTION,$ECHO_DIR))) {
        $n = $pair[0]; $lp = $pair[1]; $tp = $pair[2]
        $j = $State.Junctions[$n]
        $status = if ($j) { $j.Status } else { "n/a" }
        $tgt    = if ($j) { $j.Target } else { "n/a" }
        [void]$sb.AppendLine("- $n : $lp -> $tgt (status=$status, intended=$tp)")
    }
    [void]$sb.AppendLine("")

    [void]$sb.AppendLine("### B.3 Clones")
    foreach ($repo in @(@('JARVIS',$JARVIS_DIR,$State.Clone.Jarvis), @('ECHO',$ECHO_DIR,$State.Clone.Echo))) {
        $n = $repo[0]; $d = $repo[1]; $st = $repo[2]
        [void]$sb.AppendLine("")
        [void]$sb.AppendLine("**$n** -- status=$st, path=$d")
        if ($st -in @("cloned","exists") -and (Test-Path -LiteralPath "$d\.git")) {
            Push-Location -LiteralPath $d
            try {
                $count  = ((& git rev-list --count HEAD 2>&1) -join "").Trim()
                $branch = ((& git symbolic-ref --short HEAD 2>$null) -join "").Trim()
                $stat   = ((& git status -b --porcelain 2>&1) -join "`n").Trim()
                $size = 0
                try { $size = (Get-ChildItem -LiteralPath $d -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum } catch {}
                $sizeMb = if ($size) { [math]::Round($size/1MB, 2) } else { 0 }
                [void]$sb.AppendLine("  - branch: $branch")
                [void]$sb.AppendLine("  - commits: $count")
                [void]$sb.AppendLine("  - size: $sizeMb MB")
                [void]$sb.AppendLine("  - status:")
                [void]$sb.AppendLine('    ```')
                [void]$sb.AppendLine($stat)
                [void]$sb.AppendLine('    ```')
            } finally { Pop-Location }
        }
    }
    [void]$sb.AppendLine("")

    [void]$sb.AppendLine("### B.4 .git Attributes")
    if ($State.GitHidden.Count -eq 0) {
        [void]$sb.AppendLine("- (none recorded)")
    } else {
        foreach ($k in $State.GitHidden.Keys) {
            [void]$sb.AppendLine("- $k => $($State.GitHidden[$k])")
        }
    }
    [void]$sb.AppendLine("")

    [void]$sb.AppendLine("### B.5 Tool Versions (from Step 0)")
    foreach ($k in $State.PreFlight.Keys | Sort-Object) {
        $v = $State.PreFlight[$k]
        if ($null -eq $v -or "$v" -eq "") { $v = "(missing)" }
        [void]$sb.AppendLine("- **$k** : $v")
    }
    [void]$sb.AppendLine("")

    # Section C
    [void]$sb.AppendLine("## Section C -- Morning Review")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("### C.1 OneDrive GUI Exclusions (add manually)")
    [void]$sb.AppendLine('```')
    [void]$sb.AppendLine($State.MorningReview.OneDriveExclusions)
    [void]$sb.AppendLine('```')
    [void]$sb.AppendLine("")

    [void]$sb.AppendLine("### C.2 Tools to Install")
    if ($State.MorningReview.ToolsToInstall.Count -eq 0) {
        [void]$sb.AppendLine("- (none -- all required tools detected)")
    } else {
        foreach ($t in $State.MorningReview.ToolsToInstall) { [void]$sb.AppendLine("- $t") }
    }
    [void]$sb.AppendLine("")

    [void]$sb.AppendLine("### C.3 Git Identity")
    if ($State.MorningReview.GitIdentitySubstituted) {
        [void]$sb.AppendLine("- **SUBSTITUTED defaults applied per Sec7.1** -- verify these are correct:")
        [void]$sb.AppendLine("  - user.name  = Bill Asmar")
        [void]$sb.AppendLine("  - user.email = bill@oneillcontractors.com")
    } else {
        [void]$sb.AppendLine("- Existing identity preserved:")
        [void]$sb.AppendLine("  - user.name  = $($State.PreFlight['0.7_GitName'])")
        [void]$sb.AppendLine("  - user.email = $($State.PreFlight['0.7_GitEmail'])")
    }
    [void]$sb.AppendLine("")

    [void]$sb.AppendLine("### C.4 Junctions Needing Manual Creation")
    if ($State.MorningReview.JunctionsNeedManualCreation) {
        [void]$sb.AppendLine("- Step 3 was skipped (not elevated). Run from an **elevated** PowerShell:")
        [void]$sb.AppendLine('```powershell')
        [void]$sb.AppendLine("New-Item -ItemType Junction -Path `"$JARVIS_JUNCTION`" -Target `"$JARVIS_DIR`"")
        [void]$sb.AppendLine("New-Item -ItemType Junction -Path `"$ECHO_JUNCTION`"   -Target `"$ECHO_DIR`"")
        [void]$sb.AppendLine('```')
    } else {
        [void]$sb.AppendLine("- (none)")
    }
    [void]$sb.AppendLine("")

    [void]$sb.AppendLine("### C.5 Auth Failures")
    if ($State.MorningReview.AuthFailures.Count -eq 0) {
        [void]$sb.AppendLine("- (none)")
    } else {
        foreach ($a in $State.MorningReview.AuthFailures) { [void]$sb.AppendLine("- $a") }
    }
    [void]$sb.AppendLine("")

    [void]$sb.AppendLine("### C.6 OneDrive Conflict-File Detections")
    if ($State.MorningReview.ConflictDetections.Count -eq 0) {
        [void]$sb.AppendLine("- (none)")
    } else {
        foreach ($c in $State.MorningReview.ConflictDetections) { [void]$sb.AppendLine("- $c") }
    }
    [void]$sb.AppendLine("")

    [void]$sb.AppendLine("### C.7 Staged-But-Uncommitted ECHO Scaffold Files")
    if ($State.EchoScaffold.Staged -and $State.EchoScaffold.Staged.Count -gt 0) {
        [void]$sb.AppendLine("- Branch: **$($State.EchoScaffold.Branch)**")
        foreach ($f in $State.EchoScaffold.Staged) { [void]$sb.AppendLine("- $f") }
    } else {
        [void]$sb.AppendLine("- (none staged)")
    }
    [void]$sb.AppendLine("")

    # Section D
    [void]$sb.AppendLine("## Section D -- Ready for Phase 1?")
    [void]$sb.AppendLine("")
    $s1 = $State.Steps["Step 1"]
    $s4 = $State.Steps["Step 4"]
    $s5 = $State.Steps["Step 5"]
    $reasons = @()
    if ($s1 -ne "ok") { $reasons += "Step 1 = '$s1'" }
    if ($s4 -ne "ok") { $reasons += "Step 4 = '$s4'" }
    if ($s5 -ne "ok") { $reasons += "Step 5 = '$s5'" }
    if ($State.HardStopFired) { $reasons += "hard-stop fired" }

    if ($reasons.Count -eq 0) {
        [void]$sb.AppendLine("**Ready for Phase 1: YES**")
    } else {
        [void]$sb.AppendLine("**Ready for Phase 1: NO** -- $($reasons -join '; ')")
    }
    [void]$sb.AppendLine("")

    $reportText = $sb.ToString()
    Set-Content -LiteralPath $REPORT -Value $reportText -Encoding UTF8
    Log-Entry "Step 7b: wrote bootstrap-report.md"

    Write-Host ""
    Write-Host "============== REPORT =============="
    Write-Host $reportText
    Write-Host "====================================="
    Write-Host ""

    $State.Steps["Step 7"] = "ok"
    Log-Entry "Step 7 END -- run complete. HALT per Sec4."
} catch {
    $State.Steps["Step 7"] = "failed: $(Truncate-Text $_.Exception.Message 200)"
    Log-Entry "Step 7 FAILED: $(Redact-Secrets $_.Exception.Message)"
}

Log-Entry "=== Bootstrap run END ==="
