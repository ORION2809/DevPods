#Requires -Version 5.1
<#
.SYNOPSIS
    T2 Host-Audio Proof Runner: injects TTS WAV fixtures into emulator microphone.

.DESCRIPTION
    Validates real Android STT through the emulator host-audio path
    (-allow-host-audio). The reliable Windows setup is VB-Cable:

    - Default playback: Speakers (VB-Audio Virtual Cable)
    - Default recording: CABLE Output (VB-Audio Virtual Cable)

    The runner waits for Android's own "ready for speech" log before each
    fixture playback. This avoids racing SpeechRecognizer startup.
#>
param(
    [int]$SessionCount = 5,
    [string]$ArtifactId = "t2-host-audio-$(Get-Date -Format 'yyyyMMdd-HHmmss')",
    [switch]$AutoInstallCable,
    [switch]$NoBuild,
    [switch]$NoReport,
    [int]$ReadyTimeoutSec = 75,
    [int]$CompletionTimeoutSec = 420
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$projectRoot = Resolve-Path (Join-Path $scriptDir "../..")
$androidRelay = Join-Path $projectRoot "android-relay"
$gradle = Join-Path $androidRelay "gradlew.bat"
$apkPath = Join-Path $androidRelay "app/build/outputs/apk/debug/app-debug.apk"
$fixturesDir = Join-Path $scriptDir "tts-fixtures"
$artifactDir = Join-Path (Join-Path $scriptDir "proof-runs") $ArtifactId
$legacyArtifactDir = Join-Path $scriptDir "proof-artifacts"
$logSelectors = @(
    "RelayServiceDebugExt:D",
    "OpenClawRelay:D",
    "AndroidRuntime:E",
    "EmulatedMicrophone:D",
    "EmulatorHostAudio:D",
    "RecognitionService:D",
    "SodaSpeechRecognizer:D"
)

if (-not (Test-Path $artifactDir)) { New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null }
if (-not (Test-Path $legacyArtifactDir)) { New-Item -ItemType Directory -Path $legacyArtifactDir -Force | Out-Null }

function Write-Step($msg) { Write-Host "[T2] $msg" -ForegroundColor Cyan }
function Write-Ok($msg) { Write-Host "[OK] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Write-Err($msg) { Write-Host "[ERR] $msg" -ForegroundColor Red }

function Get-AdbLogcatLines {
    $args = @("logcat", "-d", "-v", "time", "-s") + $logSelectors
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & adb @args 2>$null
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Get-LogMatchCount {
    param([string]$Pattern)

    $lines = Get-AdbLogcatLines
    return @($lines | Select-String -Pattern $Pattern).Count
}

function Wait-ForLogCount {
    param(
        [string]$Pattern,
        [int]$ExpectedCount,
        [int]$TimeoutSec,
        [string]$Description
    )

    $startedAt = Get-Date
    while (((Get-Date) - $startedAt).TotalSeconds -lt $TimeoutSec) {
        $count = Get-LogMatchCount -Pattern $Pattern
        if ($count -ge $ExpectedCount) {
            Write-Ok "$Description observed ($count/$ExpectedCount)"
            return $true
        }
        Start-Sleep -Milliseconds 500
    }

    $count = Get-LogMatchCount -Pattern $Pattern
    Write-Warn "Timed out waiting for $Description ($count/$ExpectedCount after ${TimeoutSec}s)."
    return $false
}

function Wait-ForLogPattern {
    param(
        [string]$Pattern,
        [int]$TimeoutSec,
        [string]$Description
    )

    return Wait-ForLogCount -Pattern $Pattern -ExpectedCount 1 -TimeoutSec $TimeoutSec -Description $Description
}

function Play-Fixture {
    param(
        [string]$FixtureName,
        [string]$Label
    )

    $fixturePath = Join-Path $fixturesDir $FixtureName
    if (-not (Test-Path $fixturePath)) {
        Write-Err "Fixture not found: $fixturePath"
        return $false
    }

    Write-Step "$Label : playing $FixtureName"
    $process = Start-Process `
        -FilePath $ffplayCommand.Source `
        -ArgumentList @("-autoexit", "-nodisp", "-volume", "100", $fixturePath) `
        -PassThru `
        -WindowStyle Hidden

    if (-not $process.WaitForExit(12000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        Write-Warn "$FixtureName playback exceeded 12s and was stopped."
    }

    Start-Sleep -Milliseconds 350
    return $true
}

function Pull-ProofArtifact {
    $localArtifact = Join-Path $artifactDir "$ArtifactId.json"
    $legacyArtifact = Join-Path $legacyArtifactDir "$ArtifactId.json"
    $remotePath = "app_proof_artifacts/$ArtifactId.json"

    $artifactText = & adb shell "run-as com.openclaw.relay cat $remotePath" 2>$null
    if ($artifactText) {
        [System.IO.File]::WriteAllText($localArtifact, $artifactText, [System.Text.Encoding]::UTF8)
        [System.IO.File]::WriteAllText($legacyArtifact, $artifactText, [System.Text.Encoding]::UTF8)
        Write-Ok "Artifact pulled to $localArtifact"
        return $localArtifact
    }

    $legacyRemoteDir = "/sdcard/Android/data/com.openclaw.relay/files/proofs"
    & adb pull "$legacyRemoteDir/$ArtifactId.json" "$localArtifact" 2>&1 | Out-Null
    if (Test-Path $localArtifact) {
        Copy-Item -Path $localArtifact -Destination $legacyArtifact -Force
        Write-Ok "Artifact pulled to $localArtifact"
        return $localArtifact
    }

    Write-Warn "Artifact not found on device. Check logcat for errors."
    return $null
}

function Capture-ProofEvidence {
    Write-Step "Capturing proof evidence..."

    $logcatPath = Join-Path $artifactDir "android-logcat.txt"
    $args = @("logcat", "-d", "-v", "time", "-s") + $logSelectors
    & adb @args > $logcatPath 2>$null

    $uiCaptureScript = Join-Path $scriptDir "capture-android-ui.ps1"
    if (Test-Path $uiCaptureScript) {
        & $uiCaptureScript -OutputDir $artifactDir -Prefix "post-proof" *> $null
    }

    $bridgeUIPath = Join-Path $scriptDir "capture-bridge-ui.ts"
    if ((Test-Path $bridgeUIPath) -and -not $NoReport) {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & npx tsx $bridgeUIPath $artifactDir "post-proof" *> $null
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
    }

    if (-not $NoReport) {
        $reportScript = Join-Path $scriptDir "generate-proof-report.ts"
        if (Test-Path $reportScript) {
            & npx tsx $reportScript $artifactDir "$artifactDir\proof-report.html" 2>$null | Out-Null
            if (Test-Path "$artifactDir\proof-report.html") {
                Write-Ok "Report generated: $artifactDir\proof-report.html"
            }
        }
    }
}

# --- Phase 1: Environment checks ---
Write-Step "Checking environment..."

& adb devices | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Err "ADB not available. Make sure Android SDK platform-tools are in PATH."
    exit 1
}

$ffplayCommand = Get-Command ffplay -ErrorAction SilentlyContinue
if (-not $ffplayCommand) {
    Write-Err "ffplay not found in PATH. Install FFmpeg and rerun T2 proof."
    exit 1
}

$emulatorHostAudio = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match "emulator|qemu" -and $_.CommandLine -match "-allow-host-audio" } |
    Select-Object -First 1
if (-not $emulatorHostAudio) {
    Write-Warn "Running emulator process was not detected with -allow-host-audio. T2 will likely fail."
    Write-Warn "Relaunch with: emulator -avd Aqua_API35 -allow-host-audio -no-snapshot-save"
} else {
    Write-Ok "Emulator host audio flag detected."
}

$fixtureFiles = @("run_tests.wav", "what_branch.wav", "open_file.wav", "check_status.wav", "deploy_staging.wav", "stop.wav")
$missing = $fixtureFiles | Where-Object { -not (Test-Path (Join-Path $fixturesDir $_)) }
if ($missing) {
    Write-Step "Generating TTS fixtures..."
    $python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $python) { $python = Get-Command python3 -ErrorAction SilentlyContinue }
    if (-not $python) {
        Write-Err "Python not found. Install Python 3.11+ and edge-tts."
        exit 1
    }
    & $python.Source (Join-Path $scriptDir "generate-tts-fixtures.py")
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Fixture generation failed."
        exit 1
    }
}

$audioEndpoints = @(Get-PnpDevice -Class AudioEndpoint -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -match "VB-Audio|Virtual Cable|CABLE" })
$hasCablePlayback = @($audioEndpoints | Where-Object { $_.FriendlyName -match "Speakers \(VB-Audio Virtual Cable\)" }).Count -gt 0
$hasCableRecording = @($audioEndpoints | Where-Object { $_.FriendlyName -match "CABLE Output \(VB-Audio Virtual Cable\)" }).Count -gt 0
$hasCable = $hasCablePlayback -and $hasCableRecording

if ($hasCable) {
    Write-Ok "VB-Cable endpoints detected."
} else {
    Write-Warn "VB-Cable endpoints were not both detected."
    if ($AutoInstallCable) {
        Write-Step "Attempting to install VB-Cable (requires admin)..."
        $installScript = Join-Path $scriptDir "install-vb-cable.ps1"
        if (Test-Path $installScript) {
            Start-Process powershell -ArgumentList "-ExecutionPolicy Bypass -File `"$installScript`"" -Verb RunAs -Wait
        }
    }
}

# --- Phase 2: Build and install ---
if (-not $NoBuild) {
    Write-Step "Building debug APK..."
    Push-Location $androidRelay
    & $gradle :app:assembleDebug --quiet
    Pop-Location
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Build failed."
        exit 1
    }
    Write-Ok "APK built."
}

if (-not (Test-Path $apkPath)) {
    Write-Err "APK not found at $apkPath"
    exit 1
}

Write-Step "Installing APK..."
& adb install -r -d $apkPath | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Err "APK install failed."
    exit 1
}
Write-Ok "APK installed."

& adb shell pm grant com.openclaw.relay android.permission.RECORD_AUDIO 2>$null | Out-Null
& adb shell pm grant com.openclaw.relay android.permission.POST_NOTIFICATIONS 2>$null | Out-Null
& adb shell cmd appops set com.openclaw.relay RECORD_AUDIO allow 2>$null | Out-Null

# --- Phase 3: Launch app and start proof ---
Write-Step "Launching app and starting T2 proof..."
& adb logcat -c
& adb shell am force-stop com.openclaw.relay 2>$null | Out-Null
Start-Sleep -Seconds 1
& adb shell am start -n "com.openclaw.relay/.MainActivity" | Out-Null
Start-Sleep -Seconds 3

& adb shell am broadcast -a "com.openclaw.relay.action.RUN_PROOF" `
    --es "proofTier" "T2_EMULATOR_HOST_AUDIO" `
    --ei "sessionCount" $SessionCount `
    --es "artifactId" $ArtifactId `
    -n "com.openclaw.relay/.DebugProofRunnerReceiver" | Out-Null

Write-Ok "T2 proof broadcast sent."

$ackPattern = "debug proof run started tier=T2_EMULATOR_HOST_AUDIO sessions=$SessionCount artifactId=$ArtifactId"
if (-not (Wait-ForLogPattern -Pattern $ackPattern -TimeoutSec 30 -Description "proof runner ACK")) {
    Capture-ProofEvidence
    Write-Err "Proof runner did not start."
    exit 1
}

# --- Phase 4: Inject audio fixtures on Android readiness ---
$fixtureMap = @(
    "run_tests.wav",
    "what_branch.wav",
    "open_file.wav",
    "check_status.wav",
    "deploy_staging.wav"
)

$readyPattern = "speech recognizer ready for speech sessionId="
$readyCount = 0

for ($i = 0; $i -lt $SessionCount; $i++) {
    $readyCount += 1
    if (-not (Wait-ForLogCount -Pattern $readyPattern -ExpectedCount $readyCount -TimeoutSec $ReadyTimeoutSec -Description "STT readiness for session $i")) {
        break
    }

    Start-Sleep -Milliseconds 250
    $fixtureName = $fixtureMap[$i % $fixtureMap.Length]
    [void](Play-Fixture -FixtureName $fixtureName -Label "Session $i")

    $sessionPattern = "session $i (completed|did not complete within timeout)"
    [void](Wait-ForLogPattern -Pattern $sessionPattern -TimeoutSec 45 -Description "completion marker for session $i")
}

for ($i = 0; $i -lt 5; $i++) {
    $readyCount += 1
    if (-not (Wait-ForLogCount -Pattern $readyPattern -ExpectedCount $readyCount -TimeoutSec $ReadyTimeoutSec -Description "STT readiness for barge-in $i")) {
        break
    }

    Start-Sleep -Milliseconds 250
    [void](Play-Fixture -FixtureName "stop.wav" -Label "Barge-in $i")

    $bargePattern = "barge-in trial $i (recorded|was not recorded within timeout|speech session did not complete within timeout)"
    [void](Wait-ForLogPattern -Pattern $bargePattern -TimeoutSec 45 -Description "barge-in marker $i")
}

# --- Phase 5: Wait for completion ---
Write-Step "Waiting for proof completion..."
$completionPattern = "debug proof run completed artifactId=$ArtifactId"
$completed = Wait-ForLogPattern -Pattern $completionPattern -TimeoutSec $CompletionTimeoutSec -Description "proof completion"
if (-not $completed) {
    Write-Warn "Proof did not complete within $CompletionTimeoutSec seconds; pulling evidence anyway."
}

# --- Phase 6: Pull artifacts and report ---
Write-Step "Pulling proof artifact..."
$artifactPath = Pull-ProofArtifact
Capture-ProofEvidence

$status = "UNKNOWN"
$successCount = 0
$targetCount = $SessionCount
if ($artifactPath -and (Test-Path $artifactPath)) {
    try {
        $artifact = Get-Content $artifactPath -Raw | ConvertFrom-Json
        $status = $artifact.status
        $successCount = $artifact.successfulSessionCount
        $targetCount = $artifact.targetSessionCount
        Write-Ok "Artifact status: $status"
        Write-Ok "Successful sessions: $successCount/$targetCount"
        Write-Ok "Route successes: $($artifact.routeSuccessCount)"
        Write-Ok "STT successes: $($artifact.sttSuccessCount)"
        Write-Ok "Barge-in target hits: $($artifact.interruptionTargetMetCount)"
        if ($artifact.failureReasons -and $artifact.failureReasons.Count -gt 0) {
            Write-Warn "Failure reasons: $($artifact.failureReasons -join ', ')"
        }
    } catch {
        Write-Warn "Could not parse proof artifact JSON: $($_.Exception.Message)"
    }
}

Write-Ok "T2 proof evidence directory: $artifactDir"
if ($status -eq "PASSED") {
    Write-Host "`n=== T2 HOST AUDIO PROOF PASSED ===" -ForegroundColor Green
} elseif ($status -eq "PARTIAL") {
    Write-Host "`n=== T2 HOST AUDIO PROOF PARTIAL ===" -ForegroundColor Yellow
} else {
    Write-Host "`n=== T2 HOST AUDIO PROOF FAILED ===" -ForegroundColor Red
    if (-not $hasCable) {
        Write-Warn "Failure may be due to missing VB-Cable endpoints."
    }
}
