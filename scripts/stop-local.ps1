<#
.SYNOPSIS
  Stops the complete local RMI observability environment.
#>

$ErrorActionPreference = "Continue"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$PidFile = Join-Path $RepoRoot "logs\load-generator.pid"

Write-Host "=== Stopping Local RMI Environment ===" -ForegroundColor Cyan

if (Test-Path -LiteralPath $PidFile) {
    $loadPid = Get-Content -LiteralPath $PidFile -ErrorAction SilentlyContinue
    if ($loadPid -and (Get-Process -Id $loadPid -ErrorAction SilentlyContinue)) {
        Stop-Process -Id $loadPid -Force -ErrorAction SilentlyContinue
        Write-Host "[OK] Load generator stopped (PID $loadPid)" -ForegroundColor Green
    } else {
        Write-Host "[SKIP] Load generator is not running" -ForegroundColor DarkGray
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
    "$PSScriptRoot\stop-observability.ps1"

Write-Host "=== Local RMI Environment Stopped ===" -ForegroundColor Cyan
