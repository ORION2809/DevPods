#Requires -Version 5.1
<#
.SYNOPSIS
    Captures Android UI screenshots and hierarchy for proof artifacts.

.DESCRIPTION
    Uses ADB screencap and uiautomator to capture the current Android UI state.
    Saves PNG screenshots and XML hierarchy to the specified artifact directory.

.PARAMETER OutputDir
    Directory to save screenshots and hierarchy files.

.PARAMETER Prefix
    Filename prefix for this capture batch (e.g., "setup", "session-5", "failure").
#>
param(
    [Parameter(Mandatory=$true)][string]$OutputDir,
    [string]$Prefix = "ui"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Path $OutputDir | Out-Null }

function Write-Step($msg) { Write-Host "[UI-Capture] $msg" -ForegroundColor Cyan }

function Invoke-AdbQuiet {
    param([Parameter(Mandatory=$true)][string[]]$AdbArgs)

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & adb @AdbArgs *> $null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

# Screenshot via ADB
$pngPath = Join-Path $OutputDir "$Prefix-screenshot.png"
Write-Step "Capturing screenshot -> $pngPath"
Invoke-AdbQuiet -AdbArgs @("shell", "screencap", "-p", "/sdcard/screen.png") | Out-Null
Invoke-AdbQuiet -AdbArgs @("pull", "/sdcard/screen.png", "$pngPath") | Out-Null
if (Test-Path $pngPath) { Write-Host "  OK: $pngPath" -ForegroundColor Green }
else { Write-Host "  WARN: screenshot failed" -ForegroundColor Yellow }

# UI hierarchy via uiautomator
$xmlPath = Join-Path $OutputDir "$Prefix-hierarchy.xml"
Write-Step "Capturing UI hierarchy -> $xmlPath"
Invoke-AdbQuiet -AdbArgs @("shell", "uiautomator", "dump", "/sdcard/window_dump.xml") | Out-Null
Invoke-AdbQuiet -AdbArgs @("pull", "/sdcard/window_dump.xml", "$xmlPath") | Out-Null
if (Test-Path $xmlPath) { Write-Host "  OK: $xmlPath" -ForegroundColor Green }
else { Write-Host "  WARN: hierarchy failed" -ForegroundColor Yellow }

# dumpsys audio
$audioPath = Join-Path $OutputDir "$Prefix-dumpsys-audio.txt"
Write-Step "Pulling dumpsys audio -> $audioPath"
& adb shell dumpsys audio > "$audioPath" 2>$null
if ((Get-Item $audioPath).Length -gt 0) { Write-Host "  OK: $audioPath" -ForegroundColor Green }

# dumpsys bluetooth_manager
$btPath = Join-Path $OutputDir "$Prefix-dumpsys-bluetooth.txt"
Write-Step "Pulling dumpsys bluetooth_manager -> $btPath"
& adb shell dumpsys bluetooth_manager > "$btPath" 2>$null
if ((Get-Item $btPath).Length -gt 0) { Write-Host "  OK: $btPath" -ForegroundColor Green }

# dumpsys media_session
$mediaPath = Join-Path $OutputDir "$Prefix-dumpsys-media.txt"
Write-Step "Pulling dumpsys media_session -> $mediaPath"
& adb shell dumpsys media_session > "$mediaPath" 2>$null
if ((Get-Item $mediaPath).Length -gt 0) { Write-Host "  OK: $mediaPath" -ForegroundColor Green }

Write-Step "UI capture complete for prefix=$Prefix"
