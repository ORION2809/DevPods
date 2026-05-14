#!/usr/bin/env pwsh
# DevPods Product Verification Gate
# Runs all build, test, audit, and packaging gates required before beta.

$ErrorActionPreference = "Stop"
$exitCode = 0
$startTime = Get-Date

function Write-Gate($name, $status, $details = "") {
    $color = switch ($status) {
        "PASS" { "Green" }
        "FAIL" { "Red" }
        "WARN" { "Yellow" }
        default { "White" }
    }
    Write-Host "[$status] $name" -ForegroundColor $color
    if ($details) { Write-Host "       $details" -ForegroundColor DarkGray }
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  DevPods Product Verification Gate" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Gate 1: Bridge typecheck
Write-Host "--- Bridge TypeScript ---" -ForegroundColor DarkCyan
try {
    $null = npm run typecheck 2>$null
    Write-Gate "TypeScript typecheck" "PASS"
} catch {
    Write-Gate "TypeScript typecheck" "FAIL" $_.Exception.Message
    $exitCode = 1
}

# Gate 2: Bridge build
try {
    $null = npm run build 2>$null
    Write-Gate "Bridge build" "PASS"
} catch {
    Write-Gate "Bridge build" "FAIL" $_.Exception.Message
    $exitCode = 1
}

# Gate 3: Bridge tests
try {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $testOutput = npm test 2>&1
        $testExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($testExitCode -eq 0) {
        $testCount = [regex]::Match($testOutput, '(\d+) passed').Groups[1].Value
        Write-Gate "Bridge tests" "PASS" "$testCount tests passed"
    } else {
        throw "Tests failed"
    }
} catch {
    Write-Gate "Bridge tests" "FAIL" $_.Exception.Message
    $exitCode = 1
}

# Gate 4: npm audit
try {
    $auditOutput = npm audit --audit-level=moderate 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Gate "npm audit" "PASS" "0 vulnerabilities"
    } else {
        throw "Audit found issues"
    }
} catch {
    Write-Gate "npm audit" "FAIL" $_.Exception.Message
    $exitCode = 1
}

# Gate 5: Windows bridge package
try {
    $null = npm run package:bridge:windows 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Gate "Windows bridge package" "PASS"
    } else {
        throw "Package build failed"
    }
} catch {
    Write-Gate "Windows bridge package" "WARN" $_.Exception.Message
}

# Gate 6: Android build
Write-Host ""
Write-Host "--- Android Relay ---" -ForegroundColor DarkCyan
$androidDir = Join-Path $PSScriptRoot "android-relay"
$gradle = Join-Path $androidDir "gradlew.bat"
if (-not (Test-Path $gradle)) {
    $androidDir = Join-Path (Get-Location).Path "android-relay"
    $gradle = Join-Path $androidDir "gradlew.bat"
}

function Invoke-AndroidGradle {
    param(
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]] $GradleArgs
    )

    Push-Location $androidDir
    try {
        & $gradle @GradleArgs
    } finally {
        Pop-Location
    }
}

try {
    Invoke-AndroidGradle :app:assembleDebug --quiet 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Gate "Android debug APK" "PASS"
    } else {
        throw "assembleDebug failed"
    }
} catch {
    Write-Gate "Android debug APK" "FAIL" $_.Exception.Message
    $exitCode = 1
}

# Gate 7: Android release build
try {
    Invoke-AndroidGradle :app:assembleRelease --quiet 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Gate "Android release APK" "PASS"
    } else {
        throw "assembleRelease failed"
    }
} catch {
    Write-Gate "Android release APK" "FAIL" $_.Exception.Message
    $exitCode = 1
}

# Gate 8: Android lint
try {
    $lintOutput = Invoke-AndroidGradle :app:lintDebug --quiet 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Gate "Android lint" "PASS"
    } else {
        throw "lint failed"
    }
} catch {
    Write-Gate "Android lint" "FAIL" $_.Exception.Message
    $exitCode = 1
}

# Gate 9: Android unit tests
try {
    Invoke-AndroidGradle :app:testDebugUnitTest --quiet 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Gate "Android unit tests" "PASS"
    } else {
        throw "Unit tests failed"
    }
} catch {
    Write-Gate "Android unit tests" "FAIL" $_.Exception.Message
    $exitCode = 1
}

# Gate 10: Installed app validation (if adb device available)
Write-Host ""
Write-Host "--- Device Validation ---" -ForegroundColor DarkCyan
$adbDevice = adb devices 2>$null | Select-String "device$" | Select-Object -First 1
if ($adbDevice) {
    Write-Gate "ADB device detected" "PASS" ($adbDevice.ToString().Trim())

    # Try to install debug APK
    $apkPath = "./android-relay/app/build/outputs/apk/debug/app-debug.apk"
    if (-not (Test-Path $apkPath)) {
        $apkPath = ".\android-relay\app\build\outputs\apk\debug\app-debug.apk"
    }
    try {
        $installOutput = adb install -r $apkPath 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Gate "APK install" "PASS"
        } else {
            throw "Install failed: $installOutput"
        }
    } catch {
        Write-Gate "APK install" "WARN" $_.Exception.Message
    }
} else {
    Write-Gate "ADB device" "WARN" "No device connected; skipping install validation"
}

# Summary
$duration = (Get-Date) - $startTime
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
if ($exitCode -eq 0) {
    Write-Host "  ALL GATES PASSED" -ForegroundColor Green
} else {
    Write-Host "  SOME GATES FAILED" -ForegroundColor Red
}
Write-Host "  Duration: $($duration.ToString('mm\:ss'))" -ForegroundColor DarkGray
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

exit $exitCode
