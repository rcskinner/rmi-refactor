<#
.SYNOPSIS
  Stops the RMI server and Jaeger container started by start-observability.ps1.

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

# --- 2. Stop Jaeger container ---
$jaegerRunning = docker ps --filter "name=^jaeger$" --format "{{.Names}}" 2>$null
if ($jaegerRunning -eq "jaeger") {
    Write-Host "Stopping Jaeger container..." -ForegroundColor Yellow
    docker stop jaeger 2>&1 | Out-Null
    Write-Host "[OK] Jaeger container stopped" -ForegroundColor Green
} else {
    Write-Host "[SKIP] Jaeger container is not running" -ForegroundColor DarkGray
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
