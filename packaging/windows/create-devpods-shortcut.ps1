param(
    [string]$Destination = [Environment]::GetFolderPath("Desktop")
)

$ErrorActionPreference = "Stop"

$appRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$targetPath = Join-Path $appRoot "start-devpods-bridge.cmd"
$iconPath = Join-Path $appRoot "devpods-bridge.ico"
$shortcutPath = Join-Path $Destination "DevPods Bridge.lnk"

if (-not (Test-Path $targetPath)) {
    throw "Bridge launcher not found at $targetPath"
}

if (-not (Test-Path $iconPath)) {
    throw "Bridge icon not found at $iconPath"
}

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $targetPath
$shortcut.WorkingDirectory = $appRoot
$shortcut.IconLocation = $iconPath
$shortcut.Description = "Start the DevPods Bridge portable bundle"
$shortcut.Save()

Write-Host "Created shortcut: $shortcutPath"
