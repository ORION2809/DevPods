[CmdletBinding()]
param(
    [string]$ApkPath = (Join-Path $PSScriptRoot '..\..\android-relay\app\build\outputs\apk\debug\app-debug.apk'),
    [string]$Activity = 'com.openclaw.relay/.MainActivity',
    [string]$PackageName = 'com.openclaw.relay',
    [string]$BaseUrl = 'http://10.0.2.2:4545',
    [string]$HostHealthUrl = 'http://127.0.0.1:4545/health',
    [string]$RelayToken = 'android-emulator-token',
    [string]$Workspace = 'current_repo'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Ensure-Command {
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function Wait-AdbLog {
    param(
        [Parameter(Mandatory = $true)][string]$Pattern,
        [int]$TimeoutSeconds = 30
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

function Quote-AdbShellValue {
    param([Parameter(Mandatory = $true)][string]$Value)

    return '\"' + $Value.Replace('"', '\"') + '\"'
}

function Invoke-RelayAction {
    param(
        [Parameter(Mandatory = $true)][string]$RelayAction,
        [string]$ServicePattern,
        [string]$ResultPattern,
        [string]$EventName,
        [string]$Utterance,
        [string]$PendingActionId
    )

    & adb logcat -c | Out-Null

    $adbArgs = @(
        'shell', 'am', 'start', '-W', '-n', $Activity,
        '--es', 'relayAction', $RelayAction,
        '--es', 'bridgeBaseUrl', $BaseUrl,
        '--es', 'relayToken', $RelayToken,
        '--es', 'workspace', $Workspace
    )

    if ($EventName) {
        $adbArgs += @('--es', 'eventName', $EventName)
    }

    if ($Utterance) {
        $adbArgs += @('--es', 'utterance', (Quote-AdbShellValue $Utterance))
    }

    if ($PendingActionId) {
        $adbArgs += @('--es', 'pendingActionId', $PendingActionId)
    }

    & adb @adbArgs | Out-Null

    $serviceLine = if ($ServicePattern) { Wait-AdbLog $ServicePattern } else { $null }
    $resultLine = if ($ResultPattern) { Wait-AdbLog $ResultPattern } else { $null }

    return [pscustomobject]@{
        relayAction = $RelayAction
        eventName = $EventName
        utterance = $Utterance
        pendingActionId = $PendingActionId
        serviceLine = $serviceLine
        resultLine = $resultLine
    }
}

function Get-BridgeHealth {
    $headers = @{}
    if ($RelayToken) {
        $headers.Authorization = "Bearer $RelayToken"
    }

    return Invoke-RestMethod -Method Get -Uri $HostHealthUrl -Headers $headers
}

function Get-ActionIdFromResult {
    param([Parameter(Mandatory = $true)][string]$ResultLine)

    $match = [regex]::Match($ResultLine, 'actionId=([^\s]+)')
    if (-not $match.Success) {
        throw "Expected an actionId in result line but none was found: $ResultLine"
    }

    return $match.Groups[1].Value
}

Ensure-Command adb

if (-not (Test-Path $ApkPath)) {
    throw "APK not found: $ApkPath"
}

if ((& adb get-state) -ne 'device') {
    throw 'adb does not currently see an attached emulator/device in the device state.'
}

$bridgeHealth = Get-BridgeHealth

& adb shell am force-stop $PackageName
& adb install -r $ApkPath | Out-Null

$startRelay = Invoke-RelayAction `
    -RelayAction 'com.openclaw.relay.action.START_RELAY' `
    -ServicePattern 'service onStartCommand action=com.openclaw.relay.action.START_RELAY event=none' `
    -ResultPattern 'health ok=true'

$checkHealth = Invoke-RelayAction `
    -RelayAction 'com.openclaw.relay.action.CHECK_HEALTH' `
    -ServicePattern 'service onStartCommand action=com.openclaw.relay.action.CHECK_HEALTH event=none' `
    -ResultPattern 'health ok=true'

$quickStatus = Invoke-RelayAction `
    -RelayAction 'com.openclaw.relay.action.DEBUG_EVENT' `
    -ServicePattern 'service onStartCommand action=com.openclaw.relay.action.DEBUG_EVENT event=android_status_shortcut' `
    -ResultPattern 'event=android_status_shortcut status=completed' `
    -EventName 'android_status_shortcut'

$headsetWake = Invoke-RelayAction `
    -RelayAction 'com.openclaw.relay.action.DEBUG_EVENT' `
    -ServicePattern 'service onStartCommand action=com.openclaw.relay.action.DEBUG_EVENT event=headset_button_single' `
    -ResultPattern 'event=headset_button_single status=acknowledged' `
    -EventName 'headset_button_single'

$approvalPrompt = Invoke-RelayAction `
    -RelayAction 'com.openclaw.relay.action.DEBUG_EVENT' `
    -ServicePattern 'service onStartCommand action=com.openclaw.relay.action.DEBUG_EVENT event=android_push_to_talk' `
    -ResultPattern 'event=android_push_to_talk status=blocked actionId=' `
    -EventName 'android_push_to_talk' `
    -Utterance 'open file docs/vision.md'
$firstActionId = Get-ActionIdFromResult $approvalPrompt.resultLine

$cancel = Invoke-RelayAction `
    -RelayAction 'com.openclaw.relay.action.CANCEL' `
    -ServicePattern 'service onStartCommand action=com.openclaw.relay.action.CANCEL event=none' `
    -ResultPattern 'event=android_cancel status=cancelled' `
    -PendingActionId $firstActionId

$postCancelNoPending = Invoke-RelayAction `
    -RelayAction 'com.openclaw.relay.action.CANCEL' `
    -ServicePattern 'service onStartCommand action=com.openclaw.relay.action.CANCEL event=none' `
    -ResultPattern 'approval skipped: no pending action for event=android_cancel'

$approvalPromptAgain = Invoke-RelayAction `
    -RelayAction 'com.openclaw.relay.action.DEBUG_EVENT' `
    -ServicePattern 'service onStartCommand action=com.openclaw.relay.action.DEBUG_EVENT event=android_push_to_talk' `
    -ResultPattern 'event=android_push_to_talk status=blocked actionId=' `
    -EventName 'android_push_to_talk' `
    -Utterance 'open file docs/vision.md'
$secondActionId = Get-ActionIdFromResult $approvalPromptAgain.resultLine

if ($firstActionId -eq $secondActionId) {
    throw "Expected a fresh approval action id after cancel, but both prompts returned $firstActionId"
}

$approve = Invoke-RelayAction `
    -RelayAction 'com.openclaw.relay.action.APPROVE' `
    -ServicePattern 'service onStartCommand action=com.openclaw.relay.action.APPROVE event=none' `
    -ResultPattern 'event=android_approve status=completed' `
    -PendingActionId $secondActionId

$postApproveNoPending = Invoke-RelayAction `
    -RelayAction 'com.openclaw.relay.action.APPROVE' `
    -ServicePattern 'service onStartCommand action=com.openclaw.relay.action.APPROVE event=none' `
    -ResultPattern 'approval skipped: no pending action for event=android_approve'

& adb logcat -c | Out-Null
& adb shell am start -W -n $Activity --es relayAction com.openclaw.relay.action.STOP_RELAY --es bridgeBaseUrl $BaseUrl --es relayToken $RelayToken --es workspace $Workspace | Out-Null
$stopDispatch = Wait-AdbLog 'service onStartCommand action=com.openclaw.relay.action.STOP_RELAY event=none'
$serviceState = (& adb shell dumpsys activity services $PackageName) -join "`n"
$serviceStopped = $serviceState -notmatch [regex]::Escape("$PackageName/.RelayService")

if (-not $serviceStopped) {
    throw "RelayService still appears active after STOP_RELAY. dumpsys output:`n$serviceState"
}

[pscustomobject]@{
    bridgeHealth = $bridgeHealth
    startRelay = $startRelay
    checkHealth = $checkHealth
    quickStatus = $quickStatus
    headsetWake = $headsetWake
    approvalPrompt = $approvalPrompt
    firstActionId = $firstActionId
    cancel = $cancel
    postCancelNoPending = $postCancelNoPending
    approvalPromptAgain = $approvalPromptAgain
    secondActionId = $secondActionId
    actionIdsDiffer = $firstActionId -ne $secondActionId
    approve = $approve
    postApproveNoPending = $postApproveNoPending
    stopDispatch = $stopDispatch
    serviceStopped = $serviceStopped
} | ConvertTo-Json -Depth 8