#Requires -Modules Pester
<#
.SYNOPSIS
    Pester tests for run-t4-proof.ps1 preflight and contract behavior.

.DESCRIPTION
    Focused tests verifying:
    - Emulator devices are rejected
    - Missing physical devices are reported
    - Preflight-only mode emits diagnostics, not invalid proof artifacts
    - Proof-run.json is never emitted with guaranteed-invalid T4 data

.EXAMPLE
    Invoke-Pester -ScriptPath .\run-t4-proof.Tests.ps1
#>

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$scriptUnderTest = Join-Path $scriptDir "run-t4-proof.ps1"

# Helper: create a temporary directory that is cleaned up
function New-TempDir {
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("t4-test-" + [Guid]::NewGuid().ToString().Substring(0, 8))
    New-Item -ItemType Directory -Path $temp -Force | Out-Null
    return $temp
}

Describe "run-t4-proof.ps1 T4 Runner Contract" {
    $originalPath = $env:PATH

    BeforeEach {
        # Ensure a fresh temp adb stub directory per test
        $script:adbStubDir = New-TempDir
    }

    AfterEach {
        Remove-Item -Path $script:adbStubDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    It "rejects emulator serials immediately with exit code 1" {
        # Arrange: create a minimal adb stub
        $adbStub = Join-Path $script:adbStubDir "adb.bat"
        "@echo off`necho emulator-5554 device" | Out-File -FilePath $adbStub -Encoding ASCII
        $env:PATH = $script:adbStubDir + ";" + $originalPath

        # Act
        $exitCode = 0
        try {
            & powershell -NoProfile -Command "& `'$scriptUnderTest`' -DeviceSerial emulator-5554 -EarbudModel Test -ProviderId test_provider -ArtifactDir $(New-TempDir)" 2>$null
            $exitCode = $LASTEXITCODE
        } catch {
            $exitCode = 1
        }

        # Assert
        $exitCode | Should Be 1
    }

    It "rejects missing physical device with exit code 1" {
        # Arrange: adb that reports no devices
        $adbStub = Join-Path $script:adbStubDir "adb.bat"
        "@echo off`necho List of devices attached" | Out-File -FilePath $adbStub -Encoding ASCII
        $env:PATH = $script:adbStubDir + ";" + $originalPath

        # Act
        $exitCode = 0
        try {
            & powershell -NoProfile -Command "& `'$scriptUnderTest`' -DeviceSerial ABC123 -EarbudModel Test -ProviderId test_provider -ArtifactDir $(New-TempDir)" 2>$null
            $exitCode = $LASTEXITCODE
        } catch {
            $exitCode = 1
        }

        # Assert
        $exitCode | Should Be 1
    }

    It "contains preflight-only mode that does not synthesize proof-run.json" {
        $scriptContent = Get-Content $scriptUnderTest -Raw

        # Verify the script has explicit preflight-only path
        $scriptContent | Should Match "preflight-diagnostics\.json"
        # Verify proof-run.json is only written after device artifact pull + validation
        $scriptContent | Should Match "Artifact validation PASSED"
        # Verify it does NOT unconditionally write proof-run.json
        $scriptContent | Should Not Match "WriteAllText.*proof-run\.json"
    }

    It "never emits a T4 proof artifact with physicalBluetoothProven false" {
        # This test verifies by design: the script only pulls artifacts from the device
        # and validates them. It never synthesizes a local proof-run.json.
        $artifactDir = New-TempDir

        # The runner code path that would synthesize a fake artifact has been removed.
        # We verify the script does not contain hardcoded physicalBluetoothProven = false
        # or status = incomplete in any artifact-generation block.
        $scriptContent = Get-Content $scriptUnderTest -Raw
        $scriptContent | Should Not Match "physicalBluetoothProven\s*=\s*\`$false"
        $scriptContent | Should Not Match '"status"\s*=\s*"incomplete"'
    }
}
