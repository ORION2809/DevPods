<#
.SYNOPSIS
    One-command emulator PCM injection proof harness.

.DESCRIPTION
    Reuses the emulator proof harness with proofTier=T1_PCM_INJECTION.
    This path injects deterministic in-memory 16 kHz mono PCM into the
    Android speech callback contract and does not require host audio.
#>
param(
    [string]$AvdName = "Aqua_API35",
    [int]$SessionCount = 20,
    [switch]$NoBuild,
    [string]$ArtifactId = "t1-pcm-$(Get-Date -Format yyyyMMddHHmmss)",
    [switch]$NoReport
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$runner = Join-Path $scriptDir "run-t1-proof.ps1"

$params = @{
    AvdName = $AvdName
    SessionCount = $SessionCount
    ArtifactId = $ArtifactId
    ProofTier = "T1_PCM_INJECTION"
}

if ($NoBuild) {
    $params.NoBuild = $true
}

if ($NoReport) {
    $params.NoReport = $true
}

& $runner @params
exit $LASTEXITCODE
