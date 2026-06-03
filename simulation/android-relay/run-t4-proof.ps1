<#
.SYNOPSIS
    T4 Physical Android + Earbuds Proof Runner.

.DESCRIPTION
    Production-grade runner for T4_PHYSICAL_ANDROID_EARBUDS proof.
    Requires a real physical Android device (emulators are rejected) with real
    earbuds paired and the DevPods Relay debug APK installed.

    DEFAULT MODE (without -RunProof):
    Runs preflight diagnostics only. Emits actionable diagnostics and exits.
    Does NOT produce a canonical proof artifact. Use this to verify your
    hardware setup before attempting a real proof run.

    PROOF MODE (with -RunProof):
    Triggers the debug proof harness on the device, waits for manual tap-to-command
    sessions and barge-in trials to complete, pulls the proof artifact, and
    validates it against the T4 contract. Only a valid artifact that passes
    validation is retained. Invalid or incomplete runs are reported honestly.

    REQUIREMENTS for proof mode:
    - Physical Android device connected via ADB (serial must be provided)
    - Bluetooth ON
    - Earbuds paired and connected
    - DevPods Relay debug APK installed
    - Operator performs 20 tap-to-command sessions and 5 barge-in trials manually

.PARAMETER DeviceSerial
    ADB device serial. REQUIRED. Must be a physical device; emulators are rejected.

.PARAMETER EarbudModel
    Earbud model name for the proof artifact (e.g., "realme Buds Air7").

.PARAMETER ProviderId
    Provider identifier (e.g., "realme_buds_air7").

.PARAMETER PhoneModel
    Phone model name for the proof artifact.

.PARAMETER SessionCount
    Number of tap-to-command sessions (default: 20).

.PARAMETER BargeInCount
    Number of barge-in trials (default: 5).

.PARAMETER BridgeBaseUrl
    Bridge server base URL for pairing.

.PARAMETER RelayToken
    Relay authentication token.

.PARAMETER ArtifactDir
    Output directory for proof artifacts (default: artifacts/proof-runs/<artifactId>).

.PARAMETER RunProof
    Actually execute a physical proof run. Without this flag, only preflight
    diagnostics are performed.

.PARAMETER NoBuild
    Skip Gradle build; install existing APK.

.PARAMETER NoReport
    Skip HTML report generation.

.PARAMETER ProofTimeoutSec
    Maximum seconds to wait for manual proof completion (default: 600).

.EXAMPLE
    # Preflight check only
    .\run-t4-proof.ps1 -DeviceSerial "ABC123" -EarbudModel "realme Buds Air7" -ProviderId "realme_buds_air7"

.EXAMPLE
    # Full physical proof run
    .\run-t4-proof.ps1 -DeviceSerial "ABC123" -EarbudModel "realme Buds Air7" -ProviderId "realme_buds_air7" -RunProof

.NOTES
    - Emulator devices are explicitly rejected. Only physical Android devices are accepted.
    - This runner does NOT fabricate physical proof. It requires real hardware.
    - All artifacts are redacted by default: no raw audio, no transcripts, no personal data.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$DeviceSerial,

    [Parameter(Mandatory = $true)]
    [string]$EarbudModel,

    [Parameter(Mandatory = $true)]
    [string]$ProviderId,

    [string]$PhoneModel = "",
    [int]$SessionCount = 20,
    [int]$BargeInCount = 5,
    [string]$BridgeBaseUrl = "",
    [string]$RelayToken = "",
    [string]$ArtifactDir = "",

    [switch]$RunProof,
    [switch]$NoBuild,
    [switch]$NoReport,
    [int]$ProofTimeoutSec = 600
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$projectRoot = Resolve-Path (Join-Path $scriptDir "../..")
$androidRelay = Join-Path $projectRoot "android-relay"
$apkPath = Join-Path $androidRelay "app/build/outputs/apk/debug/app-debug.apk"

function Write-Step {
    param([string]$Message)
    Write-Host "=== T4 PROOF: $Message ===" -ForegroundColor Cyan
}

function Write-Warn {
    param([string]$Message)
    Write-Host "WARN: $Message" -ForegroundColor Yellow
}

function Write-Fail {
    param([string]$Message)
    Write-Host "FAIL: $Message" -ForegroundColor Red
}

function Write-Ok {
    param([string]$Message)
    Write-Host "OK: $Message" -ForegroundColor Green
}

function Invoke-Adb {
    param([string[]]$Arguments)
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & adb -s $DeviceSerial @Arguments 2>$null
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Invoke-AdbShell {
    param([string]$Command)
    Invoke-Adb @("shell", $Command)
}

function Test-IsEmulatorSerial {
    param([string]$Serial)
    return $Serial -match "^emulator-"
}

# --- Phase 0: Tooling checks ---
Write-Step "Tooling checks"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Fail "adb is not installed or not in PATH"
    exit 1
}

if (-not (Get-Command npx -ErrorAction SilentlyContinue)) {
    Write-Fail "npx is not installed or not in PATH"
    exit 1
}

Write-Ok "adb and npx are available"

# --- Phase 1: Device validation ---
Write-Step "Device validation (serial: $DeviceSerial)"

# Explicit emulator rejection
if (Test-IsEmulatorSerial -Serial $DeviceSerial) {
    Write-Fail "Device serial '$DeviceSerial' is an emulator. T4 proof requires a physical Android device."
    exit 1
}

$adbDevicesRaw = adb devices -l 2>$null
$deviceLines = $adbDevicesRaw -split "`r?`n" | Select-String "\bdevice\b" | Where-Object { $_ -notmatch "List of" }
$matchingDevice = $deviceLines | Where-Object { ($_ -split '\s+')[0] -eq $DeviceSerial }

if (-not $matchingDevice) {
    Write-Fail "Device '$DeviceSerial' not found in adb devices."
    Write-Host "Available devices:" -ForegroundColor Yellow
    $deviceLines | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
    exit 1
}

$deviceDetails = $matchingDevice -split '\s+'
$deviceModel = $deviceDetails | Where-Object { $_ -match "model:" } | ForEach-Object { ($_ -split "model:")[1] }
$deviceProduct = $deviceDetails | Where-Object { $_ -match "product:" } | ForEach-Object { ($_ -split "product:")[1] }

Write-Ok "Physical device confirmed: $DeviceSerial (model=$deviceModel, product=$deviceProduct)"

# --- Phase 2: Bluetooth and earbud checks ---
Write-Step "Bluetooth and earbud checks"

$btState = Invoke-AdbShell "settings get global bluetooth_on"
$bluetoothEnabled = ($btState -eq "1")
if (-not $bluetoothEnabled) {
    Write-Fail "Bluetooth is not enabled on the device."
    exit 1
}
Write-Ok "Bluetooth is enabled"

$bondedOutput = Invoke-AdbShell "cmd bluetooth_manager list-bonded-devices"
$bondedDevices = @()
if ($bondedOutput -and $bondedOutput -notmatch "empty|no bonded") {
    $bondedDevices = @($bondedOutput -split "`r?`n" | Where-Object { $_.Trim() -ne "" })
}

if ($bondedDevices.Count -eq 0) {
    Write-Fail "No bonded Bluetooth devices found. Pair earbuds before running."
    exit 1
}
Write-Ok "Bonded Bluetooth devices: $($bondedDevices.Count)"

$androidVersion = (Invoke-AdbShell "getprop ro.build.version.release").Trim()
$phoneModelValue = if ($PhoneModel) { $PhoneModel } else { (Invoke-AdbShell "getprop ro.product.model").Trim() }

# Check if Relay app is installed
$appInstalled = Invoke-AdbShell "pm list packages com.openclaw.relay" | Select-String "com.openclaw.relay"
if (-not $appInstalled) {
    Write-Warn "Relay app not installed. It will be built and installed if proceeding to proof run."
    $appInstalled = $false
} else {
    Write-Ok "Relay app is installed"
    $appInstalled = $true
}

# --- Artifact directory setup ---
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$artifactId = "t4-${ProviderId}-$timestamp"

if (-not $ArtifactDir) {
    $ArtifactDir = Join-Path $scriptDir "proof-runs" $artifactId
}
New-Item -ItemType Directory -Path $ArtifactDir -Force | Out-Null
Write-Ok "Artifact directory: $ArtifactDir"

# --- Phase 3: Preflight diagnostics emission ---
Write-Step "Preflight diagnostics"

$preflightDiagnostics = @{
    runnerVersion = "2.0.0"
    timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    deviceSerial = $DeviceSerial
    deviceModel = $deviceModel
    deviceProduct = $deviceProduct
    phoneModel = $phoneModelValue
    androidVersion = $androidVersion
    bluetoothEnabled = $bluetoothEnabled
    bondedDeviceCount = $bondedDevices.Count
    bondedDevices = $bondedDevices
    earbudModel = $EarbudModel
    providerId = $ProviderId
    appInstalled = $appInstalled
    preflightPassed = $true
    canRunProof = $true
}

[System.IO.File]::WriteAllText(
    (Join-Path $ArtifactDir "preflight-diagnostics.json"),
    ($preflightDiagnostics | ConvertTo-Json -Depth 5),
    [System.Text.Encoding]::UTF8
)
Write-Ok "Preflight diagnostics written to $ArtifactDir\preflight-diagnostics.json"

# If not running proof, stop here cleanly
if (-not $RunProof) {
    Write-Host ""
    Write-Host "=== T4 PREFLIGHT PASSED ===" -ForegroundColor Green
    Write-Host "Device and earbuds are ready. To execute the physical proof run, add -RunProof:" -ForegroundColor Cyan
    Write-Host "  .\run-t4-proof.ps1 -DeviceSerial `"$DeviceSerial`" -EarbudModel `"$EarbudModel`" -ProviderId `"$ProviderId`" -RunProof" -ForegroundColor Cyan
    Write-Host ""
    exit 0
}

# --- Phase 4: Build and install APK ---
if (-not $appInstalled -or -not $NoBuild) {
    Write-Step "Building and installing APK"
    Push-Location $androidRelay
    try {
        $gradle = if ($env:OS -eq "Windows_NT" -or $PSVersionTable.PSEdition -eq "Desktop") { ".\gradlew.bat" } else { "./gradlew" }
        & $gradle :app:assembleDebug --quiet
        if ($LASTEXITCODE -ne 0) {
            Write-Fail "APK build failed"
            exit 1
        }
        Write-Ok "APK built"
    } finally {
        Pop-Location
    }
} else {
    Write-Step "Skipping build (-NoBuild specified or app already installed)"
}

if (-not (Test-Path $apkPath)) {
    Write-Fail "APK not found at $apkPath"
    exit 1
}

Invoke-Adb @("install", "-r", "-d", $apkPath) | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Fail "APK install failed"
    exit 1
}
Write-Ok "APK installed"

# Grant permissions
Write-Step "Granting permissions"
Invoke-AdbShell "pm grant com.openclaw.relay android.permission.RECORD_AUDIO" | Out-Null
Invoke-AdbShell "pm grant com.openclaw.relay android.permission.BLUETOOTH_CONNECT" | Out-Null
Invoke-AdbShell "pm grant com.openclaw.relay android.permission.BLUETOOTH_SCAN" | Out-Null
Invoke-AdbShell "pm grant com.openclaw.relay android.permission.POST_NOTIFICATIONS" | Out-Null

# --- Phase 5: Launch app and dispatch proof intent ---
Write-Step "Launching app and dispatching T4 proof intent"
Invoke-AdbShell "am force-stop com.openclaw.relay" | Out-Null
Start-Sleep -Seconds 1
Invoke-AdbShell "am start -n com.openclaw.relay/.MainActivity" | Out-Null
Start-Sleep -Seconds 3

Invoke-AdbShell "am broadcast -a com.openclaw.relay.action.RUN_PROOF " +
    "--es proofTier T4_PHYSICAL_ANDROID_EARBUDS " +
    "--ei sessionCount $SessionCount " +
    "--es artifactId $artifactId " +
    "--es earbudModel `"$EarbudModel`" " +
    "--es providerId $ProviderId " +
    $(if ($BridgeBaseUrl) { "--es bridgeBaseUrl `"$BridgeBaseUrl`" " } else { "" }) +
    $(if ($RelayToken) { "--es relayToken `"$RelayToken`" " } else { "" }) +
    "-n com.openclaw.relay/.DebugProofRunnerReceiver" | Out-Null

Write-Ok "T4 proof intent dispatched"

# --- Phase 6: Wait for proof completion ---
Write-Step "Waiting for physical proof completion (timeout ${ProofTimeoutSec}s)"
Write-Host "IMPORTANT: Perform $SessionCount tap-to-command sessions and $BargeInCount barge-in trials on the device now." -ForegroundColor Magenta
Write-Host "The runner will poll logcat for completion..." -ForegroundColor Magenta

Invoke-Adb @("logcat", "-c") | Out-Null
$elapsed = 0
$completed = $false
$proofRunId = $null
$statusLine = $null

while ($elapsed -lt $ProofTimeoutSec) {
    Start-Sleep -Seconds 5
    $elapsed += 5

    $logs = Invoke-Adb @("logcat", "-d", "-s", "RelayServiceDebugExt:D")
    $marker = $logs | Select-String "debug proof run completed artifactId=" | Select-Object -Last 1
    if ($marker) {
        $completed = $true
        if ($marker -match "artifactId=([^\s]+)") { $proofRunId = $matches[1] }
        if ($marker -match "status=([^\s]+)") { $statusLine = $matches[1] }
        break
    }

    # Progress indicator every 30s
    if ($elapsed % 30 -eq 0) {
        Write-Host "  ... $elapsed seconds elapsed, waiting for manual sessions to complete ..."
    }
}

if (-not $completed) {
    Write-Fail "Proof run did not complete within ${ProofTimeoutSec}s."
    # Still collect diagnostics for debugging
    & { . (Join-Path $scriptDir "capture-android-ui.ps1") -OutputDir $ArtifactDir -Prefix "post-proof" } -ErrorAction SilentlyContinue
    exit 1
}

Write-Ok "Proof run completed (runId=$proofRunId, status=$statusLine)"

# --- Phase 7: Pull artifact ---
Write-Step "Pulling proof artifact"
$localArtifact = Join-Path $ArtifactDir "$artifactId.json"
$remotePath = "app_proof_artifacts/$artifactId.json"

$artifactText = Invoke-AdbShell "run-as com.openclaw.relay cat $remotePath"
if ($artifactText) {
    [System.IO.File]::WriteAllText($localArtifact, $artifactText, [System.Text.Encoding]::UTF8)
    Write-Ok "Artifact pulled to $localArtifact"
} else {
    Write-Fail "Artifact not found on device at $remotePath"
    exit 1
}

# --- Phase 8: Validate artifact against T4 contract ---
Write-Step "Validating artifact against T4 contract"
$validatorPath = Join-Path $projectRoot "scripts\validate-proof-run.ts"

& npx tsx $validatorPath "$localArtifact" --require-tier T4_PHYSICAL_ANDROID_EARBUDS
if ($LASTEXITCODE -ne 0) {
    Write-Fail "Artifact validation FAILED. The pulled artifact does not satisfy the T4 proof contract."
    Write-Fail "This means the physical run did not meet the required thresholds (20 sessions, 19+ success, 5/5 barge-in, physicalBluetoothProven=true)."
    Write-Fail "Review the artifact at: $localArtifact"
    # Remove the invalid artifact so it cannot be mistaken for a valid T4 proof
    Remove-Item -Path $localArtifact -Force -ErrorAction SilentlyContinue
    exit 1
}
Write-Ok "Artifact validation PASSED"

# --- Phase 9: Collect diagnostics ---
Write-Step "Collecting diagnostics"
Invoke-AdbShell "dumpsys audio" | Out-File -FilePath "$ArtifactDir\dumpsys-audio.txt" -Encoding UTF8
Invoke-AdbShell "dumpsys bluetooth_manager" | Out-File -FilePath "$ArtifactDir\dumpsys-bluetooth-manager.txt" -Encoding UTF8
Invoke-AdbShell "dumpsys media_session" | Out-File -FilePath "$ArtifactDir\dumpsys-media-session.txt" -Encoding UTF8

$logcatPath = Join-Path $ArtifactDir "android-logcat.txt"
Invoke-Adb @("logcat", "-d", "-s", "RelayService:D", "RelayServiceDebugExt:D", "OpenClawProof:D") |
    Out-File -FilePath $logcatPath -Encoding UTF8

$uiCaptureScript = Join-Path $scriptDir "capture-android-ui.ps1"
if (Test-Path $uiCaptureScript) {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $uiCaptureScript -OutputDir $ArtifactDir -Prefix "post-proof" | Out-Null
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

# Bridge UI via Playwright
$bridgeUIPath = Join-Path $scriptDir "capture-bridge-ui.ts"
if ((Test-Path $bridgeUIPath) -and -not $NoReport) {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & npx tsx $bridgeUIPath $ArtifactDir "post-proof" | Out-Null
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

# --- Phase 10: Generate HTML report ---
if (-not $NoReport) {
    $reportScript = Join-Path $scriptDir "generate-proof-report.ts"
    if (Test-Path $reportScript) {
        Write-Step "Generating HTML report..."
        & npx tsx $reportScript $ArtifactDir "$ArtifactDir\proof-report.html" 2>$null | Out-Null
        if (Test-Path "$ArtifactDir\proof-report.html") {
            Write-Ok "Report generated: $ArtifactDir\proof-report.html"
        }
    }
}

# --- Summary ---
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " T4 PHYSICAL PROOF RUN COMPLETE" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Tier:        T4_PHYSICAL_ANDROID_EARBUDS"
Write-Host "  Artifact ID: $artifactId"
Write-Host "  Run ID:      $proofRunId"
Write-Host "  Device:      $DeviceSerial ($phoneModelValue)"
Write-Host "  Earbuds:     $EarbudModel ($ProviderId)"
Write-Host "  Status:      PASSED"
Write-Host "  Duration:    ${elapsed}s"
Write-Host "  Artifacts:   $ArtifactDir"
Write-Host "    - preflight-diagnostics.json"
Write-Host "    - $artifactId.json (VALIDATED)"
Write-Host "========================================" -ForegroundColor Cyan
