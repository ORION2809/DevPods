param(
    [switch]$Foreground,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'

$appRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$configPath = Join-Path $appRoot 'bridge-config.json'
$exampleConfigPath = Join-Path $appRoot 'bridge-config.example.json'

if (-not (Test-Path $configPath)) {
    Copy-Item $exampleConfigPath $configPath -Force
}

$config = Get-Content $configPath -Raw | ConvertFrom-Json

function Get-LanIpAddress {
    $candidates = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.PrefixOrigin -ne 'WellKnown'
        }

    return ($candidates | Select-Object -First 1 -ExpandProperty IPAddress)
}

function Resolve-RelayToken([string]$relayToken) {
    if (-not [string]::IsNullOrWhiteSpace($relayToken)) {
        return $relayToken.Trim()
    }

    return "relay-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
}

$host = if ([string]::IsNullOrWhiteSpace($config.host)) { '0.0.0.0' } else { $config.host.Trim() }
$port = if ($config.port) { [int]$config.port } else { 4545 }
$relayToken = Resolve-RelayToken $config.relayToken
$pairingBaseUrl = if ([string]::IsNullOrWhiteSpace($config.pairingBaseUrl)) {
    $lanIp = Get-LanIpAddress
    if ($lanIp) {
        "http://$lanIp`:$port"
    } else {
        "http://127.0.0.1`:$port"
    }
} else {
    $config.pairingBaseUrl.Trim().TrimEnd('/')
}

$config.relayToken = $relayToken
$config.pairingBaseUrl = $pairingBaseUrl
$config | ConvertTo-Json | Set-Content $configPath -Encoding UTF8

$nodePath = Join-Path $appRoot 'node.exe'
if (-not (Test-Path $nodePath)) {
    $nodePath = 'node'
}

$cliPath = Join-Path $appRoot 'dist\src\cli\jarvis-earbuds.js'
$localPairingUrl = "http://127.0.0.1:$port/pairing"

$argumentList = @(
    $cliPath,
    'start',
    '--host', $host,
    '--port', $port,
    '--relay-token', $relayToken,
    '--pairing-base-url', $pairingBaseUrl
)

if ($Foreground) {
    Set-Location $appRoot
    & $nodePath @argumentList
    exit $LASTEXITCODE
}

Start-Process -FilePath $nodePath -WorkingDirectory $appRoot -ArgumentList $argumentList | Out-Null

if (-not $NoBrowser -and $config.openBrowser -ne $false) {
    Start-Process $localPairingUrl | Out-Null
}

Write-Host "DevPods Bridge started."
Write-Host "Local pairing page: $localPairingUrl"
Write-Host "Phone pairing base URL: $pairingBaseUrl/pairing"
Write-Host "Relay token saved to bridge-config.json"