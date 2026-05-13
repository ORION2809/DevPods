param(
    [string]$OutputDir = "artifacts/windows-bridge/DevPodsBridgePortable"
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$repoRoot = Split-Path -Parent $repoRoot
$resolvedOutputDir = Join-Path $repoRoot $OutputDir

Push-Location $repoRoot
try {
    & npm run build
    if ($LASTEXITCODE -ne 0) {
        throw "npm run build failed with exit code $LASTEXITCODE"
    }

    if (Test-Path $resolvedOutputDir) {
        Remove-Item $resolvedOutputDir -Recurse -Force
    }

    New-Item -ItemType Directory -Path $resolvedOutputDir | Out-Null

    Copy-Item (Join-Path $repoRoot 'dist') (Join-Path $resolvedOutputDir 'dist') -Recurse -Force
    Copy-Item (Join-Path $repoRoot 'config') (Join-Path $resolvedOutputDir 'config') -Recurse -Force
    Copy-Item (Join-Path $repoRoot 'node_modules') (Join-Path $resolvedOutputDir 'node_modules') -Recurse -Force
    Copy-Item (Join-Path $repoRoot 'README.md') (Join-Path $resolvedOutputDir 'README.md') -Force
    Copy-Item (Join-Path $repoRoot 'SECURITY.md') (Join-Path $resolvedOutputDir 'SECURITY.md') -Force
    Copy-Item (Join-Path $repoRoot 'docs\supported-devices-matrix.json') (Join-Path $resolvedOutputDir 'supported-devices-matrix.json') -Force

    $nodeCommand = Get-Command node -ErrorAction Stop
    Copy-Item $nodeCommand.Source (Join-Path $resolvedOutputDir 'node.exe') -Force

    $packagingRoot = Join-Path $repoRoot 'packaging\windows'
    Copy-Item (Join-Path $packagingRoot 'start-devpods-bridge.ps1') (Join-Path $resolvedOutputDir 'start-devpods-bridge.ps1') -Force
    Copy-Item (Join-Path $packagingRoot 'start-devpods-bridge.cmd') (Join-Path $resolvedOutputDir 'start-devpods-bridge.cmd') -Force
    Copy-Item (Join-Path $packagingRoot 'bridge-config.example.json') (Join-Path $resolvedOutputDir 'bridge-config.example.json') -Force
    Copy-Item (Join-Path $packagingRoot 'bridge-config.example.json') (Join-Path $resolvedOutputDir 'bridge-config.json') -Force

    Write-Host "Packaged DevPods Bridge portable bundle at $resolvedOutputDir"
    Write-Host "Run start-devpods-bridge.cmd to start the bridge and open the pairing page."
} finally {
    Pop-Location
}