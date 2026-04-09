$ErrorActionPreference = "Stop"

#
# CONFIGURATION - must match install scripts
#
$nsMonitoring = "monitoring"
$nsDatabases  = "databases"
$nsArgo       = "argo"
$nsArgocd     = "argocd"

#
# HELPERS
#
function Test-NamespaceExists {
    param([string]$Name)
    try {
        kubectl get namespace $Name *> $null
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    }
}

function Scale-Up {
    param([string]$Namespace)

    Write-Host "  Scaling deployments in '$Namespace' to 1 ..."
    try {
        kubectl scale deployment --all -n $Namespace --replicas=1 2>&1 |
            Where-Object { $_ -notmatch "no objects passed" } |
            ForEach-Object { Write-Host "    $_" }
    } catch { Write-Host "    (no deployments)" }

    Write-Host "  Scaling statefulsets in '$Namespace' to 1 ..."
    try {
        kubectl scale statefulset --all -n $Namespace --replicas=1 2>&1 |
            Where-Object { $_ -notmatch "no objects passed" } |
            ForEach-Object { Write-Host "    $_" }
    } catch { Write-Host "    (no statefulsets)" }
}

function Wait-Rollout {
    param(
        [string]$Kind,
        [string]$Name,
        [string]$Namespace,
        [int]$TimeoutSeconds = 300
    )

    Write-Host "  Waiting for $Kind/$Name in '$Namespace' ..."
    kubectl rollout status "$Kind/$Name" -n $Namespace --timeout="${TimeoutSeconds}s"

    if ($LASTEXITCODE -ne 0) {
        Write-Host "  WARNING: rollout did not complete for $Kind/$Name"
    }
}

function Stop-PortForwardByPattern {
    param([string]$Pattern)

    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq "kubectl.exe" -and
            $_.CommandLine -match "port-forward" -and
            $_.CommandLine -match $Pattern
        } |
        ForEach-Object {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
}

function Start-PortForward {
    param(
        [string]$ServiceName,
        [string]$Namespace,
        [string]$PortMapping
    )

    Stop-PortForwardByPattern -Pattern $ServiceName

    Write-Host "  $ServiceName  ($PortMapping)"
    $process = Start-Process -FilePath "kubectl" `
        -ArgumentList "port-forward svc/$ServiceName -n $Namespace $PortMapping" `
        -WindowStyle Hidden `
        -PassThru

    Write-Host "    PID: $($process.Id)"
}

#
# MAIN
#
Write-Host "========================================"
Write-Host "Infrastructure Startup"
Write-Host "========================================"
Write-Host ""

#
# 1. Start Docker Desktop and wait for Kubernetes
#
Write-Host "=== Docker Desktop ==="
$dockerExe = "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"

# Check if Docker daemon is actually functional (not just if UI process exists)
try { docker info *> $null } catch {}
$dockerFunctional = ($LASTEXITCODE -eq 0)

if (-not $dockerFunctional) {
    # Kill any zombie UI processes left from winddown, then start fresh
    $zombies = Get-Process "Docker Desktop" -ErrorAction SilentlyContinue
    if ($zombies) {
        Write-Host "Cleaning up zombie Docker Desktop processes..."
        taskkill /F /IM "Docker Desktop.exe" *> $null
        Start-Sleep -Seconds 2
    }
    Write-Host "Starting Docker Desktop..."
    Start-Process $dockerExe
} else {
    Write-Host "Docker Desktop is already running and functional."
}

Write-Host "Waiting for Docker daemon..."
$waited = 0
do {
    Start-Sleep -Seconds 4
    $waited += 4
    try { docker info *> $null } catch {}
} while ($LASTEXITCODE -ne 0 -and $waited -lt 120)

if ($LASTEXITCODE -ne 0) { throw "Docker daemon did not become ready within 120s" }
Write-Host "Docker daemon ready. ($waited s)"

Write-Host "Waiting for Kubernetes node..."
$waited = 0
do {
    Start-Sleep -Seconds 5
    $waited += 5
    try { $nodes = (kubectl get nodes --no-headers 2>$null) -join "" } catch { $nodes = "" }
} while (($nodes -notmatch "Ready") -and $waited -lt 180)

if ($nodes -notmatch "Ready") { throw "Kubernetes did not become ready within 180s" }
Write-Host "Kubernetes ready. ($waited s)"
Write-Host ""

#
# 2. Start databases via Docker Compose (MySQL + PostgreSQL)
#
Write-Host "=== Databases (Docker Compose) ==="
$composeFile = (Resolve-Path "$PSScriptRoot\..\services\docker-compose.yml").Path
docker compose -f $composeFile up -d db target-db
if ($LASTEXITCODE -ne 0) { throw "Failed to start databases via Docker Compose" }

Write-Host "Waiting for databases to be healthy..."
$waited = 0
do {
    Start-Sleep -Seconds 3
    $waited += 3
    $mysqlHealth = (docker inspect --format='{{.State.Health.Status}}' services-db-1 2>$null)
    $pgHealth    = (docker inspect --format='{{.State.Health.Status}}' services-target-db-1 2>$null)
} while (($mysqlHealth -ne "healthy" -or $pgHealth -ne "healthy") -and $waited -lt 90)

if ($mysqlHealth -eq "healthy" -and $pgHealth -eq "healthy") {
    Write-Host "MySQL (localhost:3307) and PostgreSQL (localhost:5433) ready. ($waited s)"
} else {
    if ($mysqlHealth -ne "healthy") { Write-Host "WARNING: MySQL not healthy. Check: docker logs services-db-1" }
    if ($pgHealth -ne "healthy")    { Write-Host "WARNING: PostgreSQL not healthy. Check: docker logs services-target-db-1" }
}
Write-Host ""

#
# 3. Validate namespaces
#
Write-Host "=== Checking namespaces ==="
$missing = @()
foreach ($ns in @($nsDatabases, $nsArgo, $nsArgocd, $nsMonitoring)) {
    if (Test-NamespaceExists -Name $ns) {
        Write-Host "  $ns - OK"
    } else {
        Write-Host "  $ns - MISSING"
        $missing += $ns
    }
}

if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Host "ERROR: namespaces missing: $($missing -join ', ')"
    Write-Host "Run the corresponding install script(s) first:"
    Write-Host "  databases  -> install-dbs.ps1"
    Write-Host "  argo       -> install-argo-workflows.ps1"
    Write-Host "  argocd     -> install-argocd.ps1"
    Write-Host "  monitoring -> install-monitoring.ps1"
    exit 1
}

#
# 4. Scale up all namespaces
#
foreach ($ns in @($nsDatabases, $nsArgo, $nsArgocd, $nsMonitoring)) {
    Write-Host ""
    Write-Host "=== Scaling up: $ns ==="
    Scale-Up -Namespace $ns
}

#
# 5. Wait for critical rollouts
#
Write-Host ""
Write-Host "=== Waiting for rollouts ==="

Wait-Rollout -Kind "deployment" -Name "source-db" -Namespace $nsDatabases
Wait-Rollout -Kind "deployment" -Name "target-db"  -Namespace $nsDatabases

Wait-Rollout -Kind "deployment" -Name "workflow-controller" -Namespace $nsArgo
Wait-Rollout -Kind "deployment" -Name "argo-server"         -Namespace $nsArgo

Wait-Rollout -Kind "deployment"  -Name "argocd-server"                 -Namespace $nsArgocd
Wait-Rollout -Kind "deployment"  -Name "argocd-repo-server"            -Namespace $nsArgocd
Wait-Rollout -Kind "deployment"  -Name "argocd-redis"                  -Namespace $nsArgocd
Wait-Rollout -Kind "statefulset" -Name "argocd-application-controller" -Namespace $nsArgocd

Wait-Rollout -Kind "deployment"  -Name "kube-prometheus-stack-grafana"               -Namespace $nsMonitoring
Wait-Rollout -Kind "statefulset" -Name "prometheus-kube-prometheus-stack-prometheus" -Namespace $nsMonitoring
Wait-Rollout -Kind "statefulset" -Name "loki"                                        -Namespace $nsMonitoring
Wait-Rollout -Kind "statefulset" -Name "tempo"                                       -Namespace $nsMonitoring
Wait-Rollout -Kind "deployment"  -Name "opentelemetry-collector"                     -Namespace $nsMonitoring

#
# 6. Port-forwards (databases handled by Docker Compose above)
#
Write-Host ""
Write-Host "=== Starting port-forwards ==="

Start-PortForward -ServiceName "argo-server"   -Namespace $nsArgo   -PortMapping "2746:2746"
Start-PortForward -ServiceName "argocd-server" -Namespace $nsArgocd -PortMapping "8080:443"

Start-PortForward -ServiceName "kube-prometheus-stack-prometheus"   -Namespace $nsMonitoring -PortMapping "9090:9090"
Start-PortForward -ServiceName "kube-prometheus-stack-alertmanager" -Namespace $nsMonitoring -PortMapping "9093:9093"
Start-PortForward -ServiceName "kube-prometheus-stack-grafana"      -Namespace $nsMonitoring -PortMapping "3000:80"
Start-PortForward -ServiceName "prometheus-pushgateway"             -Namespace $nsMonitoring -PortMapping "9091:9091"
Start-PortForward -ServiceName "loki"                               -Namespace $nsMonitoring -PortMapping "3100:3100"
Start-PortForward -ServiceName "tempo"                              -Namespace $nsMonitoring -PortMapping "3200:3200"
Start-PortForward -ServiceName "opentelemetry-collector"            -Namespace $nsMonitoring -PortMapping "4317:4317 4318:4318"

#
# 7. Argo CD admin password (graceful if rotated)
#
Write-Host ""
$argoPwd = "<rotated - use your current password>"
try {
    $b64 = kubectl -n $nsArgocd get secret argocd-initial-admin-secret `
        -o jsonpath="{.data.password}" 2>$null
    if ($LASTEXITCODE -eq 0 -and $b64) {
        $argoPwd = [System.Text.Encoding]::UTF8.GetString(
            [System.Convert]::FromBase64String($b64)
        )
    }
} catch {}

#
# 8. Summary
#
Write-Host "========================================"
Write-Host "Infrastructure is up."
Write-Host "========================================"
Write-Host ""
Write-Host "Service                  URL"
Write-Host "------------------------ ----------------------------------------"
Write-Host "Grafana                  http://localhost:3000   (admin / admin)"
Write-Host "Prometheus               http://localhost:9090"
Write-Host "Alertmanager             http://localhost:9093"
Write-Host "Pushgateway              http://localhost:9091"
Write-Host "Loki                     http://localhost:3100"
Write-Host "Tempo                    http://localhost:3200"
Write-Host "OTel Collector (gRPC)    localhost:4317"
Write-Host "OTel Collector (HTTP)    http://localhost:4318"
Write-Host "Argo Workflows           https://localhost:2746  (skip login)"
Write-Host "Argo CD                  https://localhost:8080  (admin / $argoPwd)"
Write-Host "MySQL (Docker Compose)   localhost:3307  (appuser / apppassword)"
Write-Host "PostgreSQL (Docker Comp) localhost:5433  (appuser / apppassword)"
Write-Host ""
Write-Host "Quick checks:"
Write-Host "  Prometheus targets:  http://localhost:9090/targets"
Write-Host "  Grafana datasources: http://localhost:3000/connections/datasources"
Write-Host ""
Write-Host "To start microservices:"
Write-Host "  .\scripts\start-services.ps1"
Write-Host ""
