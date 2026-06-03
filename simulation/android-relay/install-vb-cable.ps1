#Requires -Version 5.1
<#
.SYNOPSIS
    Installs VB-Cable virtual audio device for T2 emulator host-audio testing.

.DESCRIPTION
    VB-Cable creates a virtual audio device that appears as both a playback
    and recording endpoint on Windows. This allows deterministic audio injection
    into the Android emulator's virtual microphone without relying on acoustic
    loopback or physical microphones.

    Requires administrator privileges.

    After installation:
    1. Set "CABLE Input" as the default playback device
    2. Set "CABLE Output" as the default recording device
    3. Restart the emulator with -allow-host-audio
    4. Run run-t2-proof.ps1
#>
param([switch]$SkipDownload)

$ErrorActionPreference = "Stop"

function Write-Step($msg) { Write-Host "[VB-Cable] $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "[OK] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Write-Err($msg)  { Write-Host "[ERR] $msg" -ForegroundColor Red }

function Get-VbCableState {
    $mediaDevices = @(Get-PnpDevice -Class MEDIA -ErrorAction SilentlyContinue | Where-Object {
        $_.FriendlyName -match "VB-Audio Virtual Cable|VBAudio"
    })
    $audioEndpoints = @(Get-PnpDevice -Class AudioEndpoint -ErrorAction SilentlyContinue | Where-Object {
        $_.FriendlyName -match "VB-Audio Virtual Cable|CABLE Output|CABLE Input"
    })
    $playbackEndpoints = @($audioEndpoints | Where-Object {
        $_.FriendlyName -match "Speakers \(VB-Audio Virtual Cable\)|CABLE Input"
    })
    $recordingEndpoints = @($audioEndpoints | Where-Object {
        $_.FriendlyName -match "CABLE Output"
    })

    [pscustomobject]@{
        Installed = ($mediaDevices.Count -gt 0 -and $audioEndpoints.Count -gt 0)
        MediaDevices = $mediaDevices
        AudioEndpoints = $audioEndpoints
        PlaybackEndpoints = $playbackEndpoints
        RecordingEndpoints = $recordingEndpoints
    }
}

$downloadUrl = "https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack43.zip"
$zipPath = "$env:TEMP\VBCABLE_Driver_Pack43.zip"
$extractDir = "$env:TEMP\VBCABLE_Driver"
$setupExe = "$extractDir\VBCABLE_Setup_x64.exe"

$preInstallState = Get-VbCableState
if ($preInstallState.Installed) {
    Write-Ok "VB-Cable is already installed."
    Write-Host ""
    Write-Host "Detected endpoints:" -ForegroundColor Yellow
    $preInstallState.AudioEndpoints | ForEach-Object { Write-Host "  - $($_.FriendlyName) [$($_.Status)]" }
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Yellow
    Write-Host "  1. Set 'Speakers (VB-Audio Virtual Cable)' or 'CABLE Input' as default playback device"
    Write-Host "  2. Set 'CABLE Output (VB-Audio Virtual Cable)' as default recording device"
    Write-Host "  3. Restart the emulator with -allow-host-audio"
    Write-Host "  4. Run: .\run-t2-proof.ps1"
    exit 0
}

# Check admin only when installation is still needed.
$currentPrincipal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Err "This script must be run as Administrator. Please open PowerShell as Admin and retry."
    exit 1
}

if (-not $SkipDownload) {
    Write-Step "Downloading VB-Cable driver..."
    try {
        Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath -UseBasicParsing
        Write-Ok "Downloaded to $zipPath"
    } catch {
        Write-Err "Failed to download VB-Cable: $_"
        exit 1
    }

    Write-Step "Extracting..."
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force
    Write-Ok "Extracted to $extractDir"
}

if (-not (Test-Path $setupExe)) {
    Write-Err "Setup executable not found at $setupExe"
    exit 1
}

Write-Step "Installing VB-Cable driver (silent)..."
try {
    $installProcess = Start-Process -FilePath $setupExe -ArgumentList "/S" -Wait -PassThru -WindowStyle Hidden
    $exitCode = $installProcess.ExitCode
} catch {
    Write-Warn "Installer process threw an error: $_"
    $exitCode = $null
}

Start-Sleep -Seconds 3
$postInstallState = Get-VbCableState

if (-not $postInstallState.Installed) {
    Write-Err "VB-Cable installation failed. Installer exit code: $exitCode"
    exit 1
}

if ($exitCode -and $exitCode -ne 0) {
    Write-Warn "Installer returned exit code $exitCode, but VB-Cable endpoints are present."
}

Write-Ok "VB-Cable installed successfully"
Write-Host ""
Write-Host "Detected endpoints:" -ForegroundColor Yellow
$postInstallState.AudioEndpoints | ForEach-Object { Write-Host "  - $($_.FriendlyName) [$($_.Status)]" }
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Yellow
Write-Host "  1. Open Windows Sound Settings"
Write-Host "  2. Set 'Speakers (VB-Audio Virtual Cable)' or 'CABLE Input' as default playback device"
Write-Host "  3. Set 'CABLE Output (VB-Audio Virtual Cable)' as default recording device"
Write-Host "  4. Restart the emulator with -allow-host-audio"
Write-Host "  5. Run: .\run-t2-proof.ps1"
Write-Host ""
