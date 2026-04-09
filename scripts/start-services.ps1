$ErrorActionPreference = "Stop"

$root = (Resolve-Path "$PSScriptRoot\..\services").Path

$services = @(
    @{ Name = "customer-service";    Port = 8081 },
    @{ Name = "account-service";     Port = 8082 },
    @{ Name = "transaction-service"; Port = 8083 }
)

#
# HELPERS
#
function Test-Port {
    param([int]$Port)
    return (Test-NetConnection -ComputerName localhost -Port $Port `
        -InformationLevel Quiet -WarningAction SilentlyContinue 2>$null)
}

function Assert-MySqlReachable {
    Write-Host "=== MySQL check ==="

    if (Test-Port 3307) {
        Write-Host "  localhost:3307  OK"
        return
    }

    throw "MySQL is unreachable on localhost:3307.`n" +
          "Make sure startup.ps1 ran successfully (it starts MySQL via Docker Compose).`n" +
          "  docker ps | findstr mysql"
}

#
# MAIN
#
Write-Host "========================================"
Write-Host "Starting Microservices"
Write-Host "========================================"
Write-Host ""

#
# 1. Verify MySQL is reachable (started by startup.ps1 via Docker Compose)
#
Assert-MySqlReachable
Write-Host ""

#
# 2. Start each service as a hidden background process.
#    Start order matters: customer-service must be up before account-service,
#    which must be up before transaction-service (Feign deps).
#    Logs go to services/<name>/target/service.log
#
Write-Host "=== Starting services ==="
foreach ($svc in $services) {
    $name    = $svc.Name
    $port    = $svc.Port
    $svcDir  = Join-Path $root $name
    $logDir  = Join-Path $svcDir "target"
    $logFile = Join-Path $logDir "service.log"

    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

    Start-Process -FilePath "cmd.exe" `
        -ArgumentList "/c cd /d `"$svcDir`" && .\mvnw.cmd spring-boot:run" `
        -WindowStyle Hidden `
        -RedirectStandardOutput $logFile `
        -RedirectStandardError (Join-Path $logDir "service-err.log")

    Write-Host "  $name  (port $port)  started  -> $logFile"

    # Brief stagger so customer-service gets a head start before account-service
    # tries its startup Feign call.
    Start-Sleep -Seconds 3
}

#
# 3. Poll health endpoints until all three report UP or timeout
#
Write-Host ""
Write-Host "=== Waiting for health ==="
Write-Host "(Polling /actuator/health every 5s, timeout 150s)"
Write-Host ""

$deadline = (Get-Date).AddSeconds(150)
$allUp    = $false

while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5

    $readyNames = @()
    foreach ($svc in $services) {
        try {
            $resp = Invoke-WebRequest -Uri "http://localhost:$($svc.Port)/actuator/health" `
                        -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
            if ($resp.StatusCode -eq 200) {
                $readyNames += $svc.Name
            }
        } catch {}
    }

    $remaining = (($deadline - (Get-Date)).TotalSeconds -as [int])
    Write-Host ("  {0}/{1} UP   ({2}s remaining)" -f $readyNames.Count, $services.Count, $remaining)

    if ($readyNames.Count -eq $services.Count) {
        $allUp = $true
        break
    }
}

Write-Host ""

if ($allUp) {
    Write-Host "========================================"
    Write-Host "All services are UP."
    Write-Host "========================================"
} else {
    Write-Host "WARNING: Not all services reported healthy within 150s."
    Write-Host "Check the logs for errors:"
    foreach ($svc in $services) {
        Write-Host "  Get-Content services\$($svc.Name)\target\service.log -Tail 50"
    }
}

Write-Host ""
Write-Host "Service                  URL"
Write-Host "------------------------ --------------------------------"
Write-Host "customer-service         http://localhost:8081"
Write-Host "account-service          http://localhost:8082"
Write-Host "transaction-service      http://localhost:8083"
Write-Host ""
Write-Host "Health endpoints:"
foreach ($svc in $services) {
    $status = "?"
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$($svc.Port)/actuator/health" `
                 -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
        $status = if ($r.StatusCode -eq 200) { "UP" } else { "HTTP $($r.StatusCode)" }
    } catch { $status = "unreachable" }
    Write-Host ("  {0,-30} http://localhost:{1}/actuator/health  [{2}]" -f $svc.Name, $svc.Port, $status)
}
Write-Host ""
