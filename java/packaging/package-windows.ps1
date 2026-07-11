<#
  Builds the JARVIS native Windows installer (.msi) with a bundled private JRE, via jpackage.
  End users never install Java separately.

  Prerequisites on the BUILD machine (Windows):
    - JDK 21+ on PATH  (provides jpackage.exe and jlink.exe -- no extra dependency)
    - WiX Toolset v3    (candle.exe / light.exe on PATH) -- required by jpackage to emit .msi
    - To sign: Windows SDK signtool.exe on PATH + your OV/EV code-signing certificate (.pfx)

  Usage (run from the java\ directory):
    powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1
    powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1 `
        -Sign -CertPath C:\certs\jarvis.pfx -CertPassword ******

  Output: packaging\dist\JARVIS-<version>.msi
#>
param(
  [string]$Version      = "0.1.0",              # numeric only -- jpackage rejects -SNAPSHOT
  [string]$Vendor       = "JARVIS Project",
  [switch]$Sign,
  [string]$CertPath,
  [string]$CertPassword,
  [string]$TimestampUrl = "http://timestamp.digicert.com"
)
$ErrorActionPreference = "Stop"

$here    = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaDir = Split-Path -Parent $here                       # the java\ project root
$dist    = Join-Path $here "dist"
$icon    = Join-Path $here "jarvis.ico"                    # optional -- used if present
$appOut  = Join-Path $javaDir "app\target"
$jar     = Join-Path $appOut "jarvis.jar"

Write-Host "==> Building the application fat-jar..."
Push-Location $javaDir
try { & .\mvnw.cmd -q -pl app -am clean package } finally { Pop-Location }
if (-not (Test-Path $jar)) { throw "Fat jar not found at $jar" }

if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# Stage ONLY the fat jar into a clean input dir. jpackage adds every jar in --input to the
# classpath, so pointing it at target\ would also bundle original-jarvis.jar (unshaded) + build junk.
$stage = Join-Path $here "staging"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item $jar (Join-Path $stage "jarvis.jar")

$jpArgs = @(
  "--type","msi",
  "--name","JARVIS",
  "--app-version",$Version,
  "--vendor",$Vendor,
  "--description","JARVIS - local AI assistant",
  "--input",$stage,
  "--main-jar","jarvis.jar",
  "--main-class","com.jarvis.app.Main",
  "--dest",$dist,
  "--win-menu","--win-shortcut","--win-dir-chooser","--win-per-user-install"
)
if (Test-Path $icon) { $jpArgs += @("--icon",$icon) }
else { Write-Host "    (no packaging\jarvis.ico -- using the default jpackage icon)" }

Write-Host "==> Running jpackage (bundles a private JRE via jlink)..."
& jpackage @jpArgs

Remove-Item -Recurse -Force $stage

$msi = Get-ChildItem -Path $dist -Filter *.msi | Select-Object -First 1
if (-not $msi) {
  throw "jpackage produced no .msi. The WiX Toolset (candle.exe/light.exe) must be installed and on PATH."
}
Write-Host "==> Built $($msi.FullName)"

if ($Sign) {
  if (-not $CertPath) { throw "-Sign requires -CertPath pointing at your .pfx code-signing certificate." }
  Write-Host "==> Signing $($msi.Name)..."
  $signArgs = @("sign","/fd","SHA256","/f",$CertPath)
  if ($CertPassword) { $signArgs += @("/p",$CertPassword) }
  $signArgs += @("/tr",$TimestampUrl,"/td","SHA256",$msi.FullName)
  & signtool @signArgs
  Write-Host "==> Signed and timestamped."
} else {
  Write-Host "==> NOTE: this installer is UNSIGNED. Before ANY public distribution (decision D4),"
  Write-Host "         re-run with:  -Sign -CertPath <your.pfx> -CertPassword <pw>"
}
Write-Host "==> Done."
