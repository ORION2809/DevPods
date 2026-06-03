<#
.SYNOPSIS
    One-command T1 emulator synthetic proof harness.

.DESCRIPTION
    Launches emulator (if needed), installs debug APK, dispatches RUN_PROOF,
    polls logcat for completion marker, pulls artifact JSON, and prints summary.

.PARAMETER AvdName
    Name of the AVD to launch. Defaults to 'Aqua_API35'.

.PARAMETER SessionCount
    Number of synthetic proof sessions. Default 20.

.PARAMETER NoBuild
    Skip Gradle build; install existing APK from app/build/outputs/apk/debug.

.PARAMETER ArtifactId
    Custom artifact ID. Default is auto-generated timestamp.

.EXAMPLE
    .\run-t1-proof.ps1 -AvdName Aqua_API35 -SessionCount 20
#>
param(
    [string]$AvdName = "Aqua_API35",
    [int]$SessionCount = 20,
    [switch]$NoBuild,
    [string]$ArtifactId = "t1-$(Get-Date -Format yyyyMMddHHmmss)",
    [switch]$NoReport,
    [string]$ProofTier = "T1_EMULATOR_SYNTHETIC"
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$projectRoot = Resolve-Path (Join-Path $scriptDir "../..")
$androidRelay = Join-Path $projectRoot "android-relay"
$apkPath = Join-Path $androidRelay "app/build/outputs/apk/debug/app-debug.apk"
$proofTimeoutSec = 180
$tierLabel = if ($ProofTier -like "T1_PCM*") {
    "T1_PCM"
} elseif ($ProofTier -match "^(T\d+)") {
    $matches[1]
} else {
    "PROOF"
}

# Artifact output directory
$artifactDir = Join-Path (Join-Path $scriptDir "proof-runs") $ArtifactId
if (-not (Test-Path $artifactDir)) { New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null }

function Write-Step($msg) { Write-Host "[$tierLabel] $msg" -ForegroundColor Cyan }
function Write-Ok($msg) { Write-Host "[OK] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Write-Err($msg) { Write-Host "[ERR] $msg" -ForegroundColor Red }

# --- 1. Environment probe ---
Write-Step "Probing environment..."
$probeScript = Join-Path $scriptDir "probe-e2e-environment.ps1"
& $probeScript

$envFile = Join-Path $scriptDir "environment.json"
$envInfo = Get-Content $envFile -Raw | ConvertFrom-Json
if ($envInfo.tier -notmatch "T1|T2") {
    Write-Err "Machine classified as $($envInfo.tier); T1 proof requires an emulator AVD."
    exit 1
}
Write-Ok "Environment: $($envInfo.tier)"

# --- 2. Ensure emulator is running ---
Write-Step "Checking emulator state..."
$emulatorRunning = $false
try {
    $devList = & adb devices | Select-String "emulator-"
    $emulatorRunning = ($null -ne $devList)
} catch { }

if (-not $emulatorRunning) {
    Write-Step "Starting AVD $AvdName..."
    $isWindowsHost = ($env:OS -eq "Windows_NT") -or ($PSVersionTable.PSEdition -eq "Desktop")
    $emulatorCmd = if ($isWindowsHost) { "emulator.exe" } else { "emulator" }
    # Try common paths
    $sdkRoot = $env:ANDROID_SDK_ROOT
    if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_HOME }
    if (-not $sdkRoot) {
        # Guess from adb location
        $adbPath = (Get-Command adb -ErrorAction SilentlyContinue).Source
        if ($adbPath) { $sdkRoot = Resolve-Path (Join-Path (Split-Path $adbPath) "..") }
    }
    if ($sdkRoot) {
        $emulatorBin = Join-Path (Join-Path $sdkRoot "emulator") $emulatorCmd
        if (Test-Path $emulatorBin) {
            Start-Process -FilePath $emulatorBin -ArgumentList "-avd", $AvdName, "-no-snapshot-load", "-no-boot-anim" -WindowStyle Hidden
        } else {
            Write-Err "Emulator binary not found at $emulatorBin"
            exit 1
        }
    } else {
        Write-Err "ANDROID_SDK_ROOT not set and emulator not found in PATH."
        exit 1
    }

    # Wait for boot
    $bootWait = 0
    while ($bootWait -lt 120) {
        Start-Sleep -Seconds 2
        $bootWait += 2
        try {
            $booted = & adb shell getprop sys.boot_completed 2>$null | Select-String "1"
            if ($booted) { break }
        } catch { }
    }
    if ($bootWait -ge 120) {
        Write-Err "Emulator did not boot within 120 seconds."
        exit 1
    }
    Write-Ok "Emulator booted ($bootWait s)"
} else {
    Write-Ok "Emulator already running"
}

# --- 3. Build and install APK ---
if (-not $NoBuild) {
    Write-Step "Building debug APK..."
    $gradle = Join-Path $androidRelay "gradlew.bat"
    & $gradle :app:assembleDebug --quiet
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Gradle assembleDebug failed."
        exit 1
    }
    Write-Ok "APK built"
}

if (-not (Test-Path $apkPath)) {
    Write-Err "APK not found at $apkPath"
    exit 1
}

Write-Step "Installing APK..."
& adb install -r -d "$apkPath"
if ($LASTEXITCODE -ne 0) {
    Write-Err "APK install failed."
    exit 1
}
Write-Ok "APK installed"

# --- 4. Clear logcat and start service ---
Write-Step "Starting RelayService..."
& adb logcat -c

# Bring app to foreground so foreground service can start (API 31+)
& adb shell am start -n "com.openclaw.relay/.MainActivity" 2>$null
Start-Sleep -Seconds 3

# --- 5. Dispatch RUN_PROOF intent ---
Write-Step "Dispatching RUN_PROOF (sessions=$SessionCount, artifactId=$ArtifactId)..."
& adb shell am broadcast -a "com.openclaw.relay.action.RUN_PROOF" `
    --es "proofTier" "$ProofTier" `
    --ei "sessionCount" $SessionCount `
    --es "artifactId" $ArtifactId `
    -n "com.openclaw.relay/.DebugProofRunnerReceiver"

# --- 6. Poll logcat for completion marker ---
Write-Step "Polling logcat for proof completion (timeout ${proofTimeoutSec}s)..."
$elapsed = 0
$proofRunId = $null
$completed = $false
while ($elapsed -lt $proofTimeoutSec) {
    Start-Sleep -Seconds 3
    $elapsed += 3
    $logs = & adb logcat -d -s "RelayService:D" "RelayServiceDebugExt:D" "OpenClawProof:D" 2>$null
    $marker = $logs | Select-String "debug proof run completed artifactId=" | Select-Object -Last 1
    if ($marker) {
        $completed = $true
        if ($marker -match "artifactId=([^\s]+)") { $proofRunId = $matches[1] }
        break
    }
    # Also check for explicit marker
    $altMarker = $logs | Select-String "OpenClawProof: completed=" | Select-Object -Last 1
    if ($altMarker) {
        $completed = $true
        if ($altMarker -match "completed=([^\s]+)") { $proofRunId = $matches[1] }
        break
    }
}

if (-not $completed) {
    Write-Err "Proof run did not complete within ${proofTimeoutSec}s."
    exit 1
}
Write-Ok "Proof run completed (runId=$proofRunId)"

# --- 7. Pull artifacts ---
Write-Step "Pulling proof artifacts..."
$localProofDir = Join-Path $scriptDir "proof-runs"
if (-not (Test-Path $localProofDir)) { New-Item -ItemType Directory -Path $localProofDir | Out-Null }

# List remote files. Android Context.getDir("proof_artifacts", MODE_PRIVATE)
# maps to /data/data/com.openclaw.relay/app_proof_artifacts.
$remoteDir = "app_proof_artifacts"
$remoteFiles = & adb shell "run-as com.openclaw.relay ls $remoteDir/" 2>$null

# Pull this run's JSON artifact first; include other JSON files only as fallback.
$pulled = @()
$candidateFiles = @()
if ($remoteFiles) {
    $candidateFiles = @($remoteFiles -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match "\.json$" })
}
$preferred = "$ArtifactId.json"
if ($candidateFiles -contains $preferred) {
    $candidateFiles = @($preferred)
}

foreach ($file in $candidateFiles) {
    $file = $file.Trim()
    if ($file -match "\.json$") {
        $remotePath = "$remoteDir/$file"
        $pullResult = & adb shell "run-as com.openclaw.relay cat $remotePath" 2>$null
        if ($pullResult) {
            $localPath = Join-Path $artifactDir $file
        [System.IO.File]::WriteAllText($localPath, $pullResult, [System.Text.Encoding]::UTF8)
        $pulled += $localPath
        }
    }
}

if ($pulled.Count -eq 0) {
    Write-Warn "No artifact files pulled. Check remote storage paths."
} else {
    Write-Ok "Pulled $($pulled.Count) artifact(s) to $localProofDir"
}

# --- 8. Capture UI artifacts ---
Write-Step "Capturing UI artifacts..."
$uiCaptureScript = Join-Path $scriptDir "capture-android-ui.ps1"
if (Test-Path $uiCaptureScript) {
    & $uiCaptureScript -OutputDir $artifactDir -Prefix "post-proof"
} else {
    Write-Warn "capture-android-ui.ps1 not found; skipping UI capture"
}

# Bridge UI via Playwright
$bridgeUIPath = Join-Path $scriptDir "capture-bridge-ui.ts"
if ((Test-Path $bridgeUIPath) -and -not $NoReport) {
    Write-Step "Capturing bridge web UI..."
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & npx tsx $bridgeUIPath $artifactDir "post-proof" *> $null
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

# Logcat slice
$logcatPath = Join-Path $artifactDir "android-logcat.txt"
Write-Step "Pulling logcat slice..."
& adb logcat -d -s "RelayService:D" "RelayServiceDebugExt:D" "OpenClawProof:D" "SpeechInputEngine:D" "SpeechOutputEngine:D" > $logcatPath 2>$null

# --- 9. Generate HTML report ---
if (-not $NoReport) {
    $reportScript = Join-Path $scriptDir "generate-proof-report.ts"
    if (Test-Path $reportScript) {
        Write-Step "Generating HTML report..."
        & npx tsx $reportScript $artifactDir "$artifactDir\proof-report.html" 2>$null | Out-Null
        if (Test-Path "$artifactDir\proof-report.html") {
            Write-Ok "Report generated: $artifactDir\proof-report.html"
        }
    }
}

# --- 10. Print summary ---
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " $tierLabel PROOF RUN SUMMARY" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Tier:        $ProofTier"
Write-Host "  Artifact ID: $ArtifactId"
Write-Host "  Run ID:      $proofRunId"
Write-Host "  Duration:    ${elapsed}s"
Write-Host "  Artifacts:   $($pulled.Count) file(s)"
Write-Host "  Output dir:  $artifactDir"
foreach ($p in $pulled) { Write-Host "    - $(Split-Path $p -Leaf)" }
if ((Test-Path "$artifactDir\proof-report.html")) {
    Write-Host "  Report:      $artifactDir\proof-report.html"
}
Write-Host "========================================" -ForegroundColor Cyan
