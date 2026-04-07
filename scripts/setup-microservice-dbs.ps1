$ErrorActionPreference = "Stop"

#
# CONFIGURATION — matches install-dbs.ps1
#
$namespace   = "databases"
$deployment  = "source-db"
$localPort   = 3307
$mysqlRoot   = "root"
$mysqlRootPw = "apppassword"   # MYSQL_ROOT_PASSWORD from install-dbs.ps1
$appUser     = "appuser"
$appPw       = "apppassword"

$databases = @("customers_db", "accounts_db", "transactions_db")

#
# HELPERS
#
function Stop-PortForwardByPattern {
    param([string]$Pattern)

    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq "kubectl.exe" -and
            $_.CommandLine -match "port-forward" -and
            $_.CommandLine -match $Pattern
        } |
        ForEach-Object {
            Write-Host "  Stopping old port-forward PID $($_.ProcessId)"
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
}

#
# STEP 1: Ensure source-db is running
#
Write-Host ""
Write-Host "=== Scaling up $deployment ==="

kubectl scale deployment $deployment -n $namespace --replicas=1 | Out-Null

Write-Host "Waiting for pod to be ready ..."
kubectl rollout status "deployment/$deployment" -n $namespace --timeout=120s
if ($LASTEXITCODE -ne 0) {
    throw "MySQL did not become ready within 120s"
}
Write-Host "MySQL is ready."

#
# STEP 2: Create databases and grant privileges (idempotent)
#
Write-Host ""
Write-Host "=== Creating databases and granting privileges ==="

$sql = ($databases | ForEach-Object {
    "CREATE DATABASE IF NOT EXISTS ``$_`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    "GRANT ALL PRIVILEGES ON ``$_``.* TO '${appUser}'@'%';"
}) -join "`n"
$sql += "`nFLUSH PRIVILEGES;"

kubectl exec -n $namespace "deploy/$deployment" -- `
    mysql "-u$mysqlRoot" "-p$mysqlRootPw" -e $sql 2>$null

if ($LASTEXITCODE -ne 0) {
    throw "Failed to create databases"
}

Write-Host "Verifying ..."
kubectl exec -n $namespace "deploy/$deployment" -- `
    mysql "-u$mysqlRoot" "-p$mysqlRootPw" -e "SHOW DATABASES;" 2>$null | `
    Where-Object { $databases -contains $_ } | ForEach-Object { Write-Host "  OK: $_" }

#
# STEP 3: Start port-forward localhost:3307 → source-db:3306
#
Write-Host ""
Write-Host "=== Starting port-forward localhost:${localPort} -> ${deployment}:3306 ==="

Stop-PortForwardByPattern -Pattern $deployment

$pf = Start-Process -FilePath "kubectl" `
    -ArgumentList "port-forward svc/$deployment -n $namespace ${localPort}:3306" `
    -WindowStyle Hidden `
    -PassThru

Start-Sleep -Seconds 2
Write-Host "Port-forward PID: $($pf.Id)"

#
# SUMMARY
#
Write-Host ""
Write-Host "========================================"
Write-Host "Done. MySQL is accessible at localhost:$localPort"
Write-Host ""
Write-Host "Databases:"
foreach ($db in $databases) {
    Write-Host "  $db"
}
Write-Host ""
Write-Host "Credentials (default for all services):"
Write-Host "  User:     $appUser"
Write-Host "  Password: $appPw"
Write-Host ""
Write-Host "To run services (three separate terminals):"
Write-Host "  cd services\customer-service    ; .\mvnw.cmd spring-boot:run"
Write-Host "  cd services\account-service     ; .\mvnw.cmd spring-boot:run"
Write-Host "  cd services\transaction-service ; .\mvnw.cmd spring-boot:run"
Write-Host ""
Write-Host "Flyway applies schema migrations automatically on first start."
Write-Host "========================================"
