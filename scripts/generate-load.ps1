<#
.SYNOPSIS
  Generates traced ledger traffic for the local Grafana dashboard.

.DESCRIPTION
  Each worker launches the CLI at the requested rate. Every CLI invocation
  creates an OpenTelemetry trace and exports it to local Tempo. The mixed mode
  produces successful contributes, withdrawals, and balance requests. Failure
  mode targets missing plans so the error-rate and failed-trace panels move.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\generate-load.ps1 `
    -DurationSeconds 120 -RequestsPerSecond 3 -Workers 2

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\generate-load.ps1 `
    -Mode failure -DurationSeconds 60 -RequestsPerSecond 2
#>

param(
    [ValidateRange(1, 3600)]
    [int]$DurationSeconds = 120,

    [ValidateRange(1, 100)]
    [int]$RequestsPerSecond = 3,

    [ValidateRange(1, 16)]
    [int]$Workers = 2,

    [ValidateSet("mixed", "success", "failure")]
    [string]$Mode = "mixed",

    [ValidateNotNullOrEmpty()]
    [string]$PlanId = "demo-plan",

    [ValidateNotNullOrEmpty()]
    [string]$ServiceName = "ledger-loadgen"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$ClassPath = "$RepoRoot\target\classes;$RepoRoot\target\dependency\*"
$WorkerJobs = @()

if (-not (Get-NetTCPConnection -LocalPort 1099 -State Listen -ErrorAction SilentlyContinue)) {
    throw "The RMI server is not listening on port 1099. Start it with scripts\start-observability.ps1."
}

if (-not (Test-Path -LiteralPath "$RepoRoot\target\classes") -or
    -not (Test-Path -LiteralPath "$RepoRoot\target\dependency")) {
    throw "Build output is missing. Run scripts\start-observability.ps1 first."
}

$workerScript = {
    param(
        [string]$Root,
        [string]$Classpath,
        [int]$Duration,
        [int]$Rate,
        [string]$TrafficMode,
        [string]$TargetPlan,
        [string]$TraceService
    )

    $env:OTEL_SERVICE_NAME = $TraceService
    $env:OTEL_EXPORTER_OTLP_ENDPOINT = "http://127.0.0.1:4317"
    $intervalMilliseconds = [Math]::Max(1, [int](1000 / $Rate))
    $deadline = (Get-Date).AddSeconds($Duration)
    $requestNumber = 0
    $successes = 0
    $failures = 0

    while ((Get-Date) -lt $deadline) {
        $requestNumber++
        $command = "balance"
        $plan = $TargetPlan
        $arguments = @("balance", $plan)

        if ($TrafficMode -eq "failure") {
            $plan = "missing-plan-$requestNumber"
            $arguments = @("balance", $plan)
        } elseif ($TrafficMode -eq "success" -or $requestNumber % 3 -eq 1) {
            $command = "contribute"
            $arguments = @("contribute", $plan, "10.00")
        } elseif ($requestNumber % 3 -eq 2) {
            $command = "withdraw"
            $arguments = @("withdraw", $plan, "1.00")
        }

        $output = (& java "-cp" $Classpath "com.example.rmirefactor.client.RmiClient" @arguments 2>&1 | Out-String)
        if ($output -match "Error:") {
            $failures++
        } else {
            $successes++
        }
        Start-Sleep -Milliseconds $intervalMilliseconds
    }

    [PSCustomObject]@{
        Worker = $PID
        Mode = $TrafficMode
        Requests = $requestNumber
        SuccessfulProcesses = $successes
        FailedProcesses = $failures
    }
}

try {
    Write-Host "Generating $Mode traffic: $Workers worker(s), $RequestsPerSecond request(s)/second per worker, $DurationSeconds second(s)." -ForegroundColor Cyan
    Write-Host "Tempo endpoint: http://127.0.0.1:4317; service name: $ServiceName" -ForegroundColor DarkGray

    for ($worker = 1; $worker -le $Workers; $worker++) {
        $WorkerJobs += Start-Job -ScriptBlock $workerScript -ArgumentList @(
            $RepoRoot,
            $ClassPath,
            $DurationSeconds,
            $RequestsPerSecond,
            $Mode,
            $PlanId,
            $ServiceName
        )
    }

    Wait-Job -Job $WorkerJobs | Out-Null
    Receive-Job -Job $WorkerJobs
} finally {
    if ($WorkerJobs.Count -gt 0) {
        Remove-Job -Job $WorkerJobs -Force -ErrorAction SilentlyContinue
    }
}
