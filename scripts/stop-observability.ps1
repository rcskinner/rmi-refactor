<#
.SYNOPSIS
  Stops the RMI server and Docker observability stack.

.NOTES
  Run from the repository root:  powershell -ExecutionPolicy Bypass -File scripts\stop-observability.ps1
#>

$ErrorActionPreference = "Continue"

Write-Host "=== Stopping Observability Stack ===" -ForegroundColor Cyan

# --- 1. Stop RMI server (kill process on port 1099) ---
$serverPid = (Get-NetTCPConnection -LocalPort 1099 -State Listen -ErrorAction SilentlyContinue).OwningProcess
if ($serverPid) {
    Write-Host "Stopping RMI server (PID $serverPid)..." -ForegroundColor Yellow
    Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue
    Write-Host "[OK] RMI server stopped" -ForegroundColor Green
} else {
    Write-Host "[SKIP] RMI server is not running" -ForegroundColor DarkGray
}

# --- 2. Stop Docker observability stack ---
Write-Host "Stopping Docker observability stack..." -ForegroundColor Yellow
docker compose -f "$PSScriptRoot\..\docker-compose.observability.yml" down 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host "[OK] Docker observability stack stopped" -ForegroundColor Green
} else {
    Write-Host "[WARN] Docker observability stack was not running" -ForegroundColor DarkGray
}

# --- 3. Verify ports are released ---
Start-Sleep -Seconds 1
$port1099 = Get-NetTCPConnection -LocalPort 1099 -State Listen -ErrorAction SilentlyContinue
$port8081 = Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue
if (-not $port1099 -and -not $port8081) {
    Write-Host "[OK] All ports released (1099, 8081)" -ForegroundColor Green
} else {
    Write-Host "[WARN] Some ports may still be in use" -ForegroundColor Red
}

Write-Host ""
Write-Host "=== Observability Stack Stopped ===" -ForegroundColor Cyan
Write-Host ""
