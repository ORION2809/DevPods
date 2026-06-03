<#
.SYNOPSIS
    Classify the local machine for E2E automation tier (T1/T2/T3/T4).

.DESCRIPTION
    Probes AVD availability, host audio loopback capability, and physical device
    connectivity to determine which proof tiers can run on this machine.
    Emits environment.json consumed by the T1/T2/T4 harnesses.

.OUTPUTS
    simulation/android-relay/environment.json
#>
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$projectRoot = Resolve-Path (Join-Path $scriptDir "../..")
$outFile = Join-Path $scriptDir "environment.json"

function Test-AvdAvailable {
    $avdmanager = & adb devices -l 2>$null
    $hasEmulator = $avdmanager | Select-String "emulator"
    return ($null -ne $hasEmulator)
}

function Test-AvdBooted {
    try {
        $out = & adb shell getprop sys.boot_completed 2>$null | Select-String "1"
        return ($null -ne $out)
    } catch { return $false }
}

function Get-HostAudioLoopback {
    # Detect Windows Stereo Mix or macOS BlackHole / Linux ALSA loopback
    $isWindowsHost = ($env:OS -eq "Windows_NT") -or ($PSVersionTable.PSEdition -eq "Desktop")
    $isMacHost = (Get-Variable IsMacOS -ErrorAction SilentlyContinue) -and $IsMacOS

    if ($isWindowsHost) {
        $soundDevices = @(Get-CimInstance Win32_SoundDevice | Where-Object {
            $_.Name -match "Stereo Mix|What U Hear|Loopback|BlackHole|VB-Audio Virtual Cable"
        })
        $audioEndpoints = @(Get-PnpDevice -Class AudioEndpoint -ErrorAction SilentlyContinue | Where-Object {
            $_.FriendlyName -match "CABLE Output|CABLE Input|VB-Audio Virtual Cable|Stereo Mix|What U Hear|Loopback|BlackHole"
        })
        $playbackEndpoints = @($audioEndpoints | Where-Object {
            $_.FriendlyName -match "Speakers \(VB-Audio Virtual Cable\)|CABLE Input|Loopback|BlackHole"
        })
        $recordingEndpoints = @($audioEndpoints | Where-Object {
            $_.FriendlyName -match "CABLE Output|Stereo Mix|What U Hear|Loopback|BlackHole"
        })

        return [pscustomobject]@{
            Available = ($soundDevices.Count -gt 0 -or ($playbackEndpoints.Count -gt 0 -and $recordingEndpoints.Count -gt 0))
            PlaybackEndpoints = @($playbackEndpoints | ForEach-Object { $_.FriendlyName })
            RecordingEndpoints = @($recordingEndpoints | ForEach-Object { $_.FriendlyName })
            SoundDevices = @($soundDevices | ForEach-Object { $_.Name })
        }
    }
    if ($isMacHost) {
        $devices = & SwitchAudioSource -a 2>$null
        $matches = @($devices | Select-String "BlackHole|Loopback")
        return [pscustomobject]@{
            Available = ($matches.Count -gt 0)
            PlaybackEndpoints = @($matches | ForEach-Object { $_.ToString() })
            RecordingEndpoints = @($matches | ForEach-Object { $_.ToString() })
            SoundDevices = @()
        }
    }
    # Linux
    try {
        $cards = @(& aplay -l 2>$null | Select-String "Loopback")
        return [pscustomobject]@{
            Available = ($cards.Count -gt 0)
            PlaybackEndpoints = @($cards | ForEach-Object { $_.ToString() })
            RecordingEndpoints = @($cards | ForEach-Object { $_.ToString() })
            SoundDevices = @()
        }
    } catch {
        return [pscustomobject]@{
            Available = $false
            PlaybackEndpoints = @()
            RecordingEndpoints = @()
            SoundDevices = @()
        }
    }
}

function Test-PhysicalDeviceConnected {
    try {
        $devices = & adb devices -l 2>$null | Select-String "device " | Where-Object { $_ -notmatch "emulator" }
        return ($null -ne $devices)
    } catch { return $false }
}

function Get-ApiLevel {
    try {
        $level = & adb shell getprop ro.build.version.sdk 2>$null
        return [int]($level.Trim())
    } catch { return 0 }
}

$avdAvailable = Test-AvdAvailable
$avdBooted = Test-AvdBooted
$hostAudio = Get-HostAudioLoopback
$physicalDevice = Test-PhysicalDeviceConnected
$apiLevel = if ($avdBooted) { Get-ApiLevel } else { 0 }

# Tier classification
$tier = "T0_UNKNOWN"
$capabilities = @()

if ($avdAvailable -or $avdBooted) {
    $tier = "T1_EMULATOR_SYNTHETIC"
    $capabilities += "T1"
}
if (($avdAvailable -or $avdBooted) -and $hostAudio.Available) {
    $tier = "T2_EMULATOR_HOST_AUDIO"
    $capabilities += "T2"
}
if ($physicalDevice) {
    $tier = "T4_PHYSICAL_DEVICE"
    $capabilities += "T4"
}
if (-not $capabilities) {
    $capabilities += "T0"
}

$envInfo = @{
    tier = $tier
    capabilities = $capabilities
    avd = @{
        available = $avdAvailable
        booted = $avdBooted
        apiLevel = $apiLevel
    }
    hostAudio = @{
        loopbackAvailable = $hostAudio.Available
        playbackEndpoints = $hostAudio.PlaybackEndpoints
        recordingEndpoints = $hostAudio.RecordingEndpoints
        soundDevices = $hostAudio.SoundDevices
    }
    physicalDevice = @{
        connected = $physicalDevice
    }
    timestamp = [DateTimeOffset]::UtcNow.ToString("o")
    machine = $env:COMPUTERNAME
}

$json = $envInfo | ConvertTo-Json -Depth 4
$json | Set-Content -Path $outFile -Encoding UTF8
Write-Host "E2E environment probe complete: $tier"
Write-Host "  Capabilities: $($capabilities -join ', ')"
Write-Host "  AVD booted: $avdBooted (API $apiLevel)"
Write-Host "  Host audio loopback: $($hostAudio.Available)"
if ($hostAudio.Available) {
    Write-Host "  Playback endpoints: $($hostAudio.PlaybackEndpoints -join ', ')"
    Write-Host "  Recording endpoints: $($hostAudio.RecordingEndpoints -join ', ')"
}
Write-Host "  Physical device: $physicalDevice"
Write-Host "  Written to: $outFile"
