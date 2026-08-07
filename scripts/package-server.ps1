<#
.SYNOPSIS
    Builds and packages the LAN FPS Windows server bundle.

.DESCRIPTION
    Runs the Gradle fat-jar task and assembles release\server.zip containing
    everything the Windows 10 PC needs:

        server.jar
        run-server.bat
        server.properties
        arena01.json
        README_SERVER_WINDOWS.txt

    Only a JDK 17+ is required; no Android SDK, no Android Studio.

.PARAMETER SkipTests
    Skip the :shared and :server test suites (faster, less safe).

.PARAMETER OutputDir
    Where to put server.zip. Defaults to <project>\release.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\package-server.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\package-server.ps1 -SkipTests
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [string]$OutputDir
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $OutputDir) { $OutputDir = Join-Path $root 'release' }
if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Path $OutputDir | Out-Null }

Write-Host '==============================================================' -ForegroundColor Cyan
Write-Host ' LAN FPS - packaging the Windows server' -ForegroundColor Cyan
Write-Host " project: $root"
Write-Host '==============================================================' -ForegroundColor Cyan

# --- sanity: is there a JDK? -------------------------------------------------
try {
    $javaVersion = (& java -version 2>&1) -join "`n"
    Write-Host "[java] $($javaVersion.Split("`n")[0])"
} catch {
    Write-Error @'
Java was not found on PATH.

Install Temurin 17 or newer:
    https://adoptium.net/temurin/releases/?version=17
and tick "Set JAVA_HOME variable" in the installer.
'@
    exit 1
}

$gradlew = Join-Path $root 'gradlew.bat'
if (-not (Test-Path $gradlew)) { Write-Error "gradlew.bat not found in $root"; exit 1 }

# --- tests -------------------------------------------------------------------
if (-not $SkipTests) {
    Write-Host '[1/2] running tests...' -ForegroundColor Yellow
    & $gradlew ':shared:test' ':server:test' '--console=plain'
    if ($LASTEXITCODE -ne 0) { Write-Error 'tests failed'; exit 1 }
} else {
    Write-Host '[1/2] tests skipped (-SkipTests)' -ForegroundColor DarkYellow
}

# --- package -----------------------------------------------------------------
Write-Host '[2/2] building server.zip...' -ForegroundColor Yellow
& $gradlew ':server:packageServer' '--console=plain'
if ($LASTEXITCODE -ne 0) { Write-Error 'packageServer failed'; exit 1 }

$zip = Join-Path $root 'release\server.zip'
if (-not (Test-Path $zip)) { Write-Error "expected $zip but it was not produced"; exit 1 }

if ((Resolve-Path $OutputDir).Path -ne (Resolve-Path (Split-Path $zip)).Path) {
    Copy-Item $zip -Destination $OutputDir -Force
    $zip = Join-Path $OutputDir 'server.zip'
}

$size = [math]::Round((Get-Item $zip).Length / 1MB, 2)
Write-Host ''
Write-Host '==============================================================' -ForegroundColor Green
Write-Host " done:  $zip  ($size MB)" -ForegroundColor Green
Write-Host ''
Write-Host ' Next steps:'
Write-Host '   1. Unzip it somewhere simple, e.g. C:\lanfps\'
Write-Host '   2. Double-click run-server.bat'
Write-Host '   3. Allow it through the Windows firewall (Private networks)'
Write-Host '   4. Run ipconfig and type that IPv4 address on the phones'
Write-Host '==============================================================' -ForegroundColor Green
