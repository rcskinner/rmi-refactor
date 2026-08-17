<#
.SYNOPSIS
  Starts the RMI server with full observability enabled.

.DESCRIPTION
  Start the Docker observability stack first with:
    docker compose -f docker-compose.observability.yml up -d

  This script then compiles the project, starts the RMI server, waits for the
  health endpoint, and prints the URLs and CLI commands to use.

.NOTES
  Run from the repository root:  powershell -ExecutionPolicy Bypass -File scripts\start-observability.ps1
  Stop everything with:            powershell -ExecutionPolicy Bypass -File scripts\stop-observability.ps1
#>

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot

Write-Host "=== Starting Observability Stack ===" -ForegroundColor Cyan

# --- 1. Verify Tempo OTLP receiver ---
$tempoPort = Get-NetTCPConnection -LocalPort 4317 -State Listen -ErrorAction SilentlyContinue
if (-not $tempoPort) {
    throw "Tempo is not listening on port 4317. Run: docker compose -f docker-compose.observability.yml up -d"
}
Write-Host "[OK] Tempo OTLP receiver is available on port 4317" -ForegroundColor Green

# --- 2. Compile and copy dependencies ---
Write-Host "Compiling project and copying dependencies..." -ForegroundColor Yellow
Push-Location $RepoRoot
try {
    mvn compile -q 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Maven compile failed" }
    mvn dependency:copy-dependencies -DoutputDirectory=target/dependency -q 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Maven dependency copy failed" }
    Write-Host "[OK] Project compiled and dependencies copied" -ForegroundColor Green
} finally {
    Pop-Location
}

# --- 3. Start RMI server ---
$serverPid = (Get-NetTCPConnection -LocalPort 1099 -State Listen -ErrorAction SilentlyContinue).OwningProcess
if ($serverPid) {
    Write-Host "[OK] RMI server is already running (PID $serverPid)" -ForegroundColor Green
} else {
    Write-Host "Starting RMI server with observability..." -ForegroundColor Yellow
    $logDirectory = Join-Path $RepoRoot "logs"
    New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
    $serverLog = Join-Path $logDirectory "ledger-server.jsonl"
    $serverStdout = Join-Path $logDirectory "ledger-server.stdout.log"
    $env:OTEL_SERVICE_NAME = "ledger-server"
    $env:OTEL_EXPORTER_OTLP_ENDPOINT = "http://localhost:4317"
    $env:OBSERVABILITY_BIND_HOST = "0.0.0.0"
    $serverProcess = Start-Process -FilePath "java" `
        -ArgumentList "-cp", "target/classes;target/dependency/*", "com.example.rmirefactor.server.RmiServer" `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardError $serverLog `
        -RedirectStandardOutput $serverStdout `
        -PassThru
    Write-Host "[OK] RMI server started (PID $($serverProcess.Id))" -ForegroundColor Green
}

# --- 4. Wait for health endpoint ---
Write-Host "Waiting for health endpoint..." -ForegroundColor Yellow
$healthy = $false
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Seconds 1
    try {
        $response = curl.exe -s -o NUL -w "%{http_code}" http://127.0.0.1:8081/health/live 2>$null
        if ($response -eq "200") { $healthy = $true; break }
    } catch { }
}
if ($healthy) {
    Write-Host "[OK] Health endpoint is responding" -ForegroundColor Green
} else {
    Write-Host "[WARN] Health endpoint did not respond within 15 seconds" -ForegroundColor Red
}

# --- 5. Print summary ---
Write-Host ""
Write-Host "=== Observability Stack is Running ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Grafana:           http://localhost:3000" -ForegroundColor White
Write-Host "Tempo API:         http://localhost:3200" -ForegroundColor White
Write-Host "Health (live):    http://127.0.0.1:8081/health/live" -ForegroundColor White
Write-Host "Health (ready):   http://127.0.0.1:8081/health/ready" -ForegroundColor White
Write-Host "Metrics:          http://127.0.0.1:8081/metrics" -ForegroundColor White
Write-Host ""
Write-Host "Generate traces by running CLI commands:" -ForegroundColor Yellow
Write-Host "  java -cp `"target/classes;target/dependency/*`" com.example.rmirefactor.client.RmiClient contribute demo-plan 100.00"
Write-Host "  java -cp `"target/classes;target/dependency/*`" com.example.rmirefactor.client.RmiClient withdraw demo-plan 25.00"
Write-Host "  java -cp `"target/classes;target/dependency/*`" com.example.rmirefactor.client.RmiClient balance demo-plan"
Write-Host ""
Write-Host "Stop everything with:  powershell -File scripts\stop-observability.ps1" -ForegroundColor Yellow
Write-Host ""
