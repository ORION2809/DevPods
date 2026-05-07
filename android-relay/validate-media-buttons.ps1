#!/usr/bin/env pwsh
# Device Validation Script for Media Button Routing
# Run after unit tests pass to verify on-device behavior

param(
    [switch]$DumpSession = $false,
    [switch]$SimulatePress = $false,
    [switch]$CheckLogs = $false,
    [switch]$FullValidation = $false
)

$ErrorActionPreference = 'Continue'

Write-Host "=== Media Button Device Validation ===" -ForegroundColor Cyan
Write-Host ""

# Check if device is connected
$devices = adb devices | Select-String -Pattern 'device$'
if (-not $devices) {
    Write-Host "ERROR: No Android device connected" -ForegroundColor Red
    Write-Host "Connect device via USB or start emulator" -ForegroundColor Yellow
    exit 1
}

$deviceCount = ($devices | Measure-Object).Count
Write-Host "✓ Found $deviceCount connected device(s)" -ForegroundColor Green
Write-Host ""

# Function to dump MediaSession state
function Test-MediaSessionState {
    Write-Host "--- MediaSession State ---" -ForegroundColor Yellow
    $sessionDump = adb shell dumpsys media_session | Select-String -Pattern "com.openclaw.relay" -Context 0,30
    
    if (-not $sessionDump) {
        Write-Host "ERROR: OpenClawRelay session not found" -ForegroundColor Red
        Write-Host "Is the app running?" -ForegroundColor Yellow
        return $false
    }
    
    Write-Host $sessionDump | Out-String
    
    # Check for STATE_READY (state=3) or STATE_PAUSED (state=2)
    $stateMatch = $sessionDump | Select-String -Pattern 'state=(\d+)'
    if ($stateMatch) {
        $state = $stateMatch.Matches[0].Groups[1].Value
        switch ($state) {
            "1" { 
                Write-Host "❌ FAIL: PlaybackState is STATE_IDLE ($state)" -ForegroundColor Red
                Write-Host "   Android will NOT route button events to this session" -ForegroundColor Red
                return $false
            }
            "2" { 
                Write-Host "✓ PASS: PlaybackState is STATE_PAUSED ($state)" -ForegroundColor Green 
                return $true
            }
            "3" { 
                Write-Host "✓ PASS: PlaybackState is STATE_READY ($state)" -ForegroundColor Green 
                return $true
            }
            default { 
                Write-Host "⚠ WARNING: Unexpected PlaybackState ($state)" -ForegroundColor Yellow 
                return $false
            }
        }
    } else {
        Write-Host "⚠ WARNING: Could not parse PlaybackState" -ForegroundColor Yellow
        return $false
    }
}

# Function to simulate button press via ADB
function Test-ButtonSimulation {
    Write-Host "--- Simulating Button Press ---" -ForegroundColor Yellow
    Write-Host "Sending KEYCODE_HEADSETHOOK (79)..."
    
    adb shell input keyevent 79
    Start-Sleep -Milliseconds 500
    
    Write-Host "✓ Keyevent sent. Check app UI for wake signal." -ForegroundColor Green
    Write-Host "   Expected: 'Last Wake Signal: headset_button_single'" -ForegroundColor Cyan
    Write-Host ""
}

# Function to check logcat for media button events
function Test-LogcatEvents {
    Write-Host "--- Recent Media Button Events (Logcat) ---" -ForegroundColor Yellow
    $logs = adb logcat -d -v time | Select-String -Pattern 'MediaSession|RelayMediaSessionController|headset_button_single|media button|KEYCODE_HEADSETHOOK' | Select-Object -Last 20
    
    if ($logs) {
        $logs | ForEach-Object { Write-Host $_.Line }
    } else {
        Write-Host "(No relevant log entries found)" -ForegroundColor Gray
    }
    Write-Host ""
}

# Main validation flow
if ($FullValidation -or $DumpSession) {
    $sessionOk = Test-MediaSessionState
    Write-Host ""
}

if ($FullValidation -or $SimulatePress) {
    Test-ButtonSimulation
}

if ($FullValidation -or $CheckLogs) {
    Test-LogcatEvents
}

# Default behavior: run all checks
if (-not $DumpSession -and -not $SimulatePress -and -not $CheckLogs) {
    Write-Host "Running full validation..." -ForegroundColor Cyan
    Write-Host ""
    
    $sessionOk = Test-MediaSessionState
    Write-Host ""
    
    if ($sessionOk) {
        Test-ButtonSimulation
        Test-LogcatEvents
        
        Write-Host "=== Validation Summary ===" -ForegroundColor Cyan
        Write-Host "✓ MediaSession is in correct state" -ForegroundColor Green
        Write-Host "✓ Button press simulated via ADB" -ForegroundColor Green
        Write-Host ""
        Write-Host "Manual step: Press physical Bluetooth button and verify wake signal" -ForegroundColor Yellow
        Write-Host ""
    } else {
        Write-Host "=== Validation Failed ===" -ForegroundColor Red
        Write-Host "Fix PlaybackState issue before testing button press" -ForegroundColor Red
        Write-Host ""
        Write-Host "Expected fix: Add player.prepare() in RelayMediaSessionController init" -ForegroundColor Yellow
        Write-Host ""
        exit 1
    }
}

# Usage examples
Write-Host "--- Quick Commands ---" -ForegroundColor Cyan
Write-Host "  Check session state:   .\validate-media-buttons.ps1 -DumpSession" -ForegroundColor Gray
Write-Host "  Simulate button press: .\validate-media-buttons.ps1 -SimulatePress" -ForegroundColor Gray
Write-Host "  Check logs:            .\validate-media-buttons.ps1 -CheckLogs" -ForegroundColor Gray
Write-Host "  Full validation:       .\validate-media-buttons.ps1" -ForegroundColor Gray
Write-Host ""
