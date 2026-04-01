#Requires -Version 5.1
<#
.SYNOPSIS
    Argus Installer for Windows (PowerShell)

.DESCRIPTION
    Downloads argus-agent and argus-cli from GitHub releases,
    installs them to ~/.argus/, and creates the 'argus' command.

.EXAMPLE
    irm https://raw.githubusercontent.com/rlaope/argus/master/install.ps1 | iex

.EXAMPLE
    .\install.ps1 -Version v0.4.0
#>

param(
    [string]$Version = "latest"
)

$ErrorActionPreference = "Stop"

$Repo = "rlaope/argus"
$InstallDir = Join-Path $env:USERPROFILE ".argus"
$BinDir = Join-Path $InstallDir "bin"

function Write-Info  { param($msg) Write-Host "[INFO]  $msg" -ForegroundColor Cyan }
function Write-Ok    { param($msg) Write-Host "[OK]    $msg" -ForegroundColor Green }
function Write-Warn  { param($msg) Write-Host "[WARN]  $msg" -ForegroundColor Yellow }
function Write-Err   { param($msg) Write-Host "[ERROR] $msg" -ForegroundColor Red }

# --- Pre-flight checks ---

$JavaCmd = Get-Command java -ErrorAction SilentlyContinue
if (-not $JavaCmd) {
    Write-Err "Java is not installed. Argus CLI requires Java 11+."
    exit 1
}

try {
    $JavaVerOutput = & java -version 2>&1 | Select-Object -First 1
    if ($JavaVerOutput -match '"(\d+)') {
        $JavaVer = [int]$Matches[1]
        if ($JavaVer -lt 11) {
            Write-Warn "Java $JavaVer detected. Argus CLI requires Java 11+."
        }
    }
} catch {}

# --- Resolve version ---

if ($Version -eq "latest") {
    Write-Info "Resolving latest release..."
    try {
        $Release = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest" -UseBasicParsing
        $Version = $Release.tag_name
    } catch {
        Write-Warn "Could not resolve latest release. Using 'v0.4.0' as fallback."
        $Version = "v0.4.0"
    }
}

$VerNum = $Version -replace '^v', ''

Write-Host ""
Write-Host "  Argus Installer" -ForegroundColor White
Write-Host "  Version: $Version" -ForegroundColor Cyan
Write-Host "  Install: $InstallDir" -ForegroundColor Cyan
Write-Host ""

# --- Download ---

New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
New-Item -ItemType Directory -Path $BinDir -Force | Out-Null

$DownloadBase = "https://github.com/$Repo/releases/download/$Version"

Write-Info "Downloading argus-agent-${VerNum}.jar ..."
try {
    Invoke-WebRequest "$DownloadBase/argus-agent-${VerNum}.jar" -OutFile "$InstallDir\argus-agent.jar" -UseBasicParsing
    Write-Ok "argus-agent.jar"
} catch {
    Write-Err "Failed to download argus-agent. Check version: $Version"
    exit 1
}

Write-Info "Downloading argus-cli-${VerNum}-all.jar ..."
try {
    Invoke-WebRequest "$DownloadBase/argus-cli-${VerNum}-all.jar" -OutFile "$InstallDir\argus-cli.jar" -UseBasicParsing
    Write-Ok "argus-cli.jar"
} catch {
    Write-Warn "argus-cli not found in release. CLI may not be available in $Version."
}

# --- Create wrapper scripts ---

# argus.cmd - Windows batch wrapper
$ArgusCmd = @'
@echo off
setlocal

set "ARGUS_JAR=%USERPROFILE%\.argus\argus-cli.jar"

rem Find Java 11+
if defined ARGUS_JAVA_HOME (
    set "JAVA_EXE=%ARGUS_JAVA_HOME%\bin\java.exe"
    goto :run
)
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    goto :run
)
where java >nul 2>&1
if %ERRORLEVEL% equ 0 (
    set "JAVA_EXE=java"
    goto :run
)
echo Error: Java 11+ is required but not found. >&2
echo Set ARGUS_JAVA_HOME or JAVA_HOME to a Java 11+ installation. >&2
exit /b 1

:run
"%JAVA_EXE%" -jar "%ARGUS_JAR%" %*
'@
Set-Content -Path "$BinDir\argus.cmd" -Value $ArgusCmd -Encoding ASCII

# argus.ps1 - PowerShell wrapper
$ArgusPsWrapper = @'
$ArgusJar = Join-Path $env:USERPROFILE ".argus\argus-cli.jar"

$JavaExe = $null
if ($env:ARGUS_JAVA_HOME -and (Test-Path "$env:ARGUS_JAVA_HOME\bin\java.exe")) {
    $JavaExe = "$env:ARGUS_JAVA_HOME\bin\java.exe"
} elseif ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $JavaExe = "$env:JAVA_HOME\bin\java.exe"
} elseif (Get-Command java -ErrorAction SilentlyContinue) {
    $JavaExe = "java"
} else {
    Write-Error "Java 11+ is required but not found. Set ARGUS_JAVA_HOME or JAVA_HOME."
    exit 1
}

& $JavaExe -jar $ArgusJar @args
'@
Set-Content -Path "$BinDir\argus-run.ps1" -Value $ArgusPsWrapper -Encoding UTF8

# argus-agent.cmd
$AgentCmd = @'
@echo off
if "%1"=="--path" (
    echo %USERPROFILE%\.argus\argus-agent.jar
) else (
    echo Argus Agent JAR: %USERPROFILE%\.argus\argus-agent.jar
    echo.
    echo Usage:
    echo   java -javaagent:%USERPROFILE%\.argus\argus-agent.jar -jar your-app.jar
)
'@
Set-Content -Path "$BinDir\argus-agent.cmd" -Value $AgentCmd -Encoding ASCII

# --- Add to PATH ---

$UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($UserPath -notlike "*$BinDir*") {
    [Environment]::SetEnvironmentVariable("Path", "$BinDir;$UserPath", "User")
    Write-Ok "Added $BinDir to user PATH"
} else {
    Write-Ok "PATH already configured"
}

# --- Install completions ---

$CompletionsDir = Join-Path $InstallDir "completions"
New-Item -ItemType Directory -Path $CompletionsDir -Force | Out-Null

$CompBase = "https://raw.githubusercontent.com/$Repo/master/completions"
try {
    Invoke-WebRequest "$CompBase/argus.ps1" -OutFile "$CompletionsDir\argus.ps1" -UseBasicParsing 2>$null
    Write-Ok "PowerShell completions installed"
} catch {
    Write-Warn "Could not download PowerShell completions"
}

# Add completion to PowerShell profile
$PsProfile = $PROFILE.CurrentUserAllHosts
if ($PsProfile -and -not (Test-Path $PsProfile)) {
    New-Item -ItemType File -Path $PsProfile -Force | Out-Null
}
if ($PsProfile -and -not (Select-String -Path $PsProfile -Pattern 'argus.ps1' -Quiet -ErrorAction SilentlyContinue)) {
    Add-Content -Path $PsProfile -Value "`n# Argus JVM Monitor`n. `"$CompletionsDir\argus.ps1`""
    Write-Ok "Added completions to PowerShell profile"
}

# --- Done ---

Write-Host ""
Write-Host "  Argus installed successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "  Quick Start:" -ForegroundColor White
Write-Host ""
Write-Host "  1. Restart your terminal or run:" -ForegroundColor White
Write-Host "     `$env:Path = `"$BinDir;`$env:Path`"" -ForegroundColor Cyan
Write-Host ""
Write-Host "  2. Diagnose any running JVM:" -ForegroundColor White
Write-Host "     argus ps" -ForegroundColor Cyan
Write-Host "     argus histo <pid>" -ForegroundColor Cyan
Write-Host "     argus threads <pid>" -ForegroundColor Cyan
Write-Host "     argus report <pid>" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Uninstall:" -ForegroundColor White
Write-Host "     Remove-Item -Recurse ~/.argus" -ForegroundColor Cyan
Write-Host "     # Remove $BinDir from PATH in System > Environment Variables" -ForegroundColor DarkGray
Write-Host ""
