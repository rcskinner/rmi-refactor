<#
.SYNOPSIS
  Starts the complete local RMI observability environment.

.DESCRIPTION
  Starts Tempo, Prometheus, Loki, Alloy, and Grafana in Docker, then starts
  the Java RMI server. Use -GenerateLoad to launch a bounded background load
  generator as part of the same command.
#>

param(
    [switch]$GenerateLoad,

    [ValidateRange(1, 3600)]
    [int]$DurationSeconds = 120,

    [ValidateRange(1, 100)]
    [int]$RequestsPerSecond = 3,

    [ValidateRange(1, 16)]
    [int]$Workers = 2,

    [ValidateSet("mixed", "success", "failure")]
    [string]$LoadMode = "mixed"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $RepoRoot "docker-compose.observability.yml"

Write-Host "=== Starting Local RMI Environment ===" -ForegroundColor Cyan

Push-Location $RepoRoot
try {
    docker compose -f $ComposeFile up -d --remove-orphans
    if ($LASTEXITCODE -ne 0) {
        throw "Docker observability stack failed to start"
    }

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
        "$PSScriptRoot\start-observability.ps1"
    if ($LASTEXITCODE -ne 0) {
        throw "Java observability startup failed"
    }

    if ($GenerateLoad) {
        $logDirectory = Join-Path $RepoRoot "logs"
        New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
        $loadScript = Join-Path $PSScriptRoot "generate-load.ps1"
        $loadArguments =
            "-NoProfile -ExecutionPolicy Bypass -File `"$loadScript`" " +
            "-DurationSeconds $DurationSeconds " +
            "-RequestsPerSecond $RequestsPerSecond " +
            "-Workers $Workers -Mode $LoadMode"
        $loadProcess = Start-Process -FilePath "powershell.exe" `
            -ArgumentList $loadArguments `
            -WorkingDirectory $RepoRoot `
            -RedirectStandardOutput (Join-Path $logDirectory "load-generator.stdout.log") `
            -RedirectStandardError (Join-Path $logDirectory "load-generator.stderr.log") `
            -PassThru
        Set-Content -LiteralPath (Join-Path $logDirectory "load-generator.pid") `
            -Value $loadProcess.Id
        Write-Host "[OK] Load generator started (PID $($loadProcess.Id), mode $LoadMode)" `
            -ForegroundColor Green
    }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Grafana: http://localhost:3000" -ForegroundColor White
Write-Host "Dashboard: http://localhost:3000/d/rmi-refactor-overview/rmi-refactor-deployment-overview" -ForegroundColor White
Write-Host "Stop everything: powershell -ExecutionPolicy Bypass -File scripts\stop-local.ps1" -ForegroundColor Yellow
