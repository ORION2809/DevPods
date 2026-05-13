[CmdletBinding()]
param(
    [string]$ApkPath = '',
    [string]$Activity = 'com.openclaw.relay/.MainActivity',
    [string]$PackageName = 'com.openclaw.relay',
    [string]$OutputPath = '',
    [string]$BridgeBaseUrl = 'http://127.0.0.1:4545',
    [string]$RelayToken = 'android-emulator-token',
    [string]$Workspace = 'current_repo',
    [switch]$RunWakeCheck,
    [switch]$RunSttCheck
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $PSScriptRoot '..\..\android-relay\app\build\outputs\apk\debug\app-debug.apk'
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $PSScriptRoot '..\..\docs\supported-devices-matrix.json'
}

function Ensure-Command {
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function Wait-AdbLog {
    param(
        [Parameter(Mandatory = $true)][string]$Pattern,
        [int]$TimeoutSeconds = 45
    )

    $job = Start-Job -ScriptBlock {
        param($InnerPattern)
        & adb logcat -m 1 -e $InnerPattern
    } -ArgumentList $Pattern

    try {
        if (-not (Wait-Job $job -Timeout $TimeoutSeconds)) {
            Stop-Job $job | Out-Null
            throw "Timed out waiting for adb log pattern: $Pattern"
        }

        $lines = Receive-Job $job
        if (-not $lines) {
            throw "adb logcat returned no lines for pattern: $Pattern"
        }

        return (@($lines) -join "`n")
    } finally {
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-AutomationAction {
    param(
        [Parameter(Mandatory = $true)][string]$AutomationAction,
        [string]$CompletionPattern,
        [int]$TimeoutSeconds = 45,
        [switch]$NeedsBridgeConfig
    )

    & adb logcat -c | Out-Null

    $adbArgs = @(
        'shell', 'am', 'start', '-W', '-n', $Activity,
        '--es', 'relayAction', $AutomationAction
    )

    if ($NeedsBridgeConfig) {
        $adbArgs += @('--es', 'bridgeBaseUrl', $BridgeBaseUrl)
        $adbArgs += @('--es', 'relayToken', $RelayToken)
        $adbArgs += @('--es', 'workspace', $Workspace)
    }

    & adb @adbArgs | Out-Null

    if ($CompletionPattern) {
        return Wait-AdbLog -Pattern $CompletionPattern -TimeoutSeconds $TimeoutSeconds
    }

    return $null
}

function Grant-PermissionIfRequested {
    param([Parameter(Mandatory = $true)][string]$Permission)

    try {
        & adb shell pm grant $PackageName $Permission 2>$null | Out-Null
    } catch {
        Write-Verbose "pm grant failed for ${Permission}; continuing with existing runtime state and AppOps fallback."
    }
}

function Set-AppOpIfPossible {
    param(
        [Parameter(Mandatory = $true)][string]$AppOp,
        [Parameter(Mandatory = $true)][string]$Mode
    )

    try {
        & adb shell appops set $PackageName $AppOp $Mode 2>$null | Out-Null
    } catch {
        Write-Verbose "appops set failed for ${AppOp}; continuing."
    }
}

function Read-DeviceCapabilityMatrixJson {
    $xmlText = & adb shell run-as $PackageName cat shared_prefs/devpods_device_profiles.xml
    if (-not $xmlText) {
        throw 'Device capability matrix prefs were not found on the device.'
    }

    $xml = [xml]($xmlText -join "`n")
    $matrixNode = $xml.map.string | Where-Object { $_.name -eq 'capability_matrix' } | Select-Object -First 1
    if (-not $matrixNode) {
        throw 'capability_matrix was not found in devpods_device_profiles.xml.'
    }

    return $matrixNode.'#text'
}

function Merge-SupportedDevicesMatrix {
    param(
        [Parameter(Mandatory = $true)][string]$ExistingPath,
        [Parameter(Mandatory = $true)][pscustomobject]$ObservedMatrix
    )

    $existing = Get-Content -Raw -Path $ExistingPath | ConvertFrom-Json
    $entries = @($existing.entries)

    foreach ($observedEntry in @($ObservedMatrix.entries)) {
        $matchIndex = -1
        for ($index = 0; $index -lt $entries.Count; $index++) {
            if ($entries[$index].deviceModel -eq $observedEntry.deviceModel -and $entries[$index].phoneModel -eq $observedEntry.phoneModel) {
                $matchIndex = $index
                break
            }
        }

        if ($matchIndex -ge 0) {
            $entries[$matchIndex] = $observedEntry
        } else {
            $entries += $observedEntry
        }
    }

    $merged = [ordered]@{
        _comment = $existing._comment
        entries = $entries
        validationProtocol = $existing.validationProtocol
    }

    $json = $merged | ConvertTo-Json -Depth 10
    Set-Content -Path $ExistingPath -Value $json
}

Ensure-Command adb

if (-not (Test-Path $ApkPath)) {
    throw "APK not found: $ApkPath"
}

$deviceState = & adb get-state
if ($deviceState -ne 'device') {
    throw 'adb does not currently see an attached Android device.'
}

& adb install -r $ApkPath | Out-Null

Grant-PermissionIfRequested 'android.permission.CAMERA'
Grant-PermissionIfRequested 'android.permission.RECORD_AUDIO'
Grant-PermissionIfRequested 'android.permission.BLUETOOTH_CONNECT'
Grant-PermissionIfRequested 'android.permission.BLUETOOTH_SCAN'
Grant-PermissionIfRequested 'android.permission.POST_NOTIFICATIONS'
Set-AppOpIfPossible 'BLUETOOTH_SCAN' 'allow'

$probeLog = Invoke-AutomationAction -AutomationAction 'com.openclaw.relay.automation.PROBE_DEVICE' -CompletionPattern 'setup probe saved' -TimeoutSeconds 60

$wakeLog = $null
if ($RunWakeCheck) {
    & adb reverse tcp:4545 tcp:4545 | Out-Null
    $wakeLog = Invoke-AutomationAction -AutomationAction 'com.openclaw.relay.automation.TEST_WAKE' -CompletionPattern 'setup wake test completed' -TimeoutSeconds 30 -NeedsBridgeConfig
}

$sttLog = $null
if ($RunSttCheck) {
    & adb reverse tcp:4545 tcp:4545 | Out-Null
    $sttLog = Invoke-AutomationAction -AutomationAction 'com.openclaw.relay.automation.TEST_STT' -CompletionPattern 'setup stt test completed' -TimeoutSeconds 35 -NeedsBridgeConfig
}

$deviceMatrixJson = Read-DeviceCapabilityMatrixJson
$observedMatrix = $deviceMatrixJson | ConvertFrom-Json
Merge-SupportedDevicesMatrix -ExistingPath $OutputPath -ObservedMatrix $observedMatrix

[pscustomobject]@{
    outputPath = (Resolve-Path $OutputPath).Path
    entriesSeeded = @($observedMatrix.entries).Count
    probeLog = $probeLog
    wakeLog = $wakeLog
    sttLog = $sttLog
} | ConvertTo-Json -Depth 8