$ErrorActionPreference = "Stop"

#
# CONFIGURATION
#
$namespace  = "monitoring"
$valuesPath = "$PSScriptRoot\..\infrastructure\helm-values"

# Pinned chart versions (verified 2026-04-04)
$versionKubePrometheusStack = "82.16.1"
$versionLoki                = "6.55.0"
$versionPromtail            = "6.17.1"
$versionTempo               = "1.24.4"
$versionOtelCollector       = "0.147.1"
$versionPushgateway         = "3.6.0"

#
# HELPERS
#
function Stop-PortForwardByService {
    param([string]$ServiceName)

    Write-Host "Cleaning old port-forward processes for $ServiceName ..."
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq "kubectl.exe" -and
            $_.CommandLine -match "port-forward" -and
            $_.CommandLine -match $ServiceName -and
            $_.CommandLine -match $namespace
        } |
        ForEach-Object {
            Write-Host "  Stopping PID $($_.ProcessId)"
            Stop-Process -Id $_.ProcessId -Force
        }
}

function Start-PortForward {
    param(
        [string]$ServiceName,
        [string]$PortMapping  # e.g. "9090:9090" or "4317:4317 4318:4318"
    )

    Stop-PortForwardByService -ServiceName $ServiceName

    Write-Host "Starting port-forward: svc/$ServiceName $PortMapping ..."
    $process = Start-Process -FilePath "kubectl" `
        -ArgumentList "port-forward svc/$ServiceName -n $namespace $PortMapping" `
        -WindowStyle Hidden `
        -PassThru

    Write-Host "  PID: $($process.Id)"
}

function Wait-Rollout {
    param(
        [string]$Kind,   # deployment | statefulset | daemonset
        [string]$Name,
        [int]$TimeoutSeconds = 420
    )

    Write-Host "Waiting for $Kind/$Name ..."
    & kubectl rollout status "$Kind/$Name" -n $namespace --timeout="${TimeoutSeconds}s"

    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "=== Diagnostics for $Kind/$Name ==="

        & kubectl describe "$Kind/$Name" -n $namespace

        Write-Host ""
        Write-Host "--- Pods ---"
        & kubectl get pods -n $namespace -o wide

        $podName = (& kubectl get pods -n $namespace -o jsonpath="{.items[0].metadata.name}") 2>$null
        if ($podName) {
            Write-Host ""
            Write-Host "--- Logs: $podName ---"
            & kubectl logs $podName -n $namespace --tail=100
        }

        throw "Rollout failed: $Kind/$Name in namespace $namespace"
    }
}

function Wait-NamespaceDeleted {
    param([string]$Name, [int]$TimeoutSeconds = 300)

    $start = Get-Date
    while ($true) {
        kubectl get namespace $Name *> $null
        if ($LASTEXITCODE -ne 0) {
            return
        }
        if (((Get-Date) - $start).TotalSeconds -gt $TimeoutSeconds) {
            throw "Timeout waiting for namespace '$Name' to be deleted."
        }
        Start-Sleep -Seconds 5
    }
}

function Invoke-HelmInstall {
    param(
        [string]$ReleaseName,
        [string]$Chart,
        [string]$Version,
        [string]$ValuesFile
    )

    Write-Host ""
    Write-Host "--- Installing $ReleaseName (${Chart}:${Version}) ---"

    & helm upgrade --install $ReleaseName $Chart `
        --namespace $namespace `
        --version $Version `
        --values $ValuesFile `
        --timeout "15m0s"

    if ($LASTEXITCODE -ne 0) {
        throw "helm upgrade --install failed for release: $ReleaseName"
    }
}

#
# MAIN
#
Write-Host "========================================"
Write-Host "Installing Monitoring Stack"
Write-Host "Namespace: $namespace"
Write-Host "========================================"
Write-Host ""

#
# 1. Stop old port-forwards for all monitoring services
#
Write-Host "=== Stopping old port-forwards ==="
Stop-PortForwardByService -ServiceName "kube-prometheus-stack-prometheus"
Stop-PortForwardByService -ServiceName "kube-prometheus-stack-alertmanager"
Stop-PortForwardByService -ServiceName "kube-prometheus-stack-grafana"
Stop-PortForwardByService -ServiceName "prometheus-pushgateway"
Stop-PortForwardByService -ServiceName "loki"
Stop-PortForwardByService -ServiceName "tempo"
Stop-PortForwardByService -ServiceName "opentelemetry-collector"

#
# 2. Clean up existing Helm releases, then delete and recreate namespace (fresh install)
#
Write-Host ""
Write-Host "=== Removing existing Helm releases (if any) ==="
$releases = @("promtail", "opentelemetry-collector", "prometheus-pushgateway", "tempo", "loki", "kube-prometheus-stack")
foreach ($release in $releases) {
    $exists = & helm list -n $namespace -q 2>$null | Select-String -Quiet -Pattern "^$release$"
    if ($exists) {
        Write-Host "  Uninstalling $release ..."
        & helm uninstall $release -n $namespace --timeout 5m 2>&1 | Out-Null
    } else {
        Write-Host "  $release not installed, skipping."
    }
}

Write-Host ""
Write-Host "=== Deleting namespace '$namespace' for fresh install ==="
& kubectl delete namespace $namespace --ignore-not-found=true
Wait-NamespaceDeleted -Name $namespace -TimeoutSeconds 300

Write-Host "=== Creating namespace '$namespace' ==="
& kubectl create namespace $namespace
if ($LASTEXITCODE -ne 0) {
    throw "Failed to create namespace $namespace"
}

#
# 3. Add Helm repos and update
#
Write-Host ""
Write-Host "=== Adding Helm repos ==="
& helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
& helm repo add grafana https://grafana.github.io/helm-charts
& helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts

Write-Host "Updating Helm repos..."
& helm repo update
if ($LASTEXITCODE -ne 0) {
    throw "helm repo update failed"
}

#
# 4. kube-prometheus-stack: Prometheus + Alertmanager + Grafana + kube-state-metrics + node-exporter
#
Write-Host ""
Write-Host "=== Installing kube-prometheus-stack ==="
Invoke-HelmInstall `
    -ReleaseName "kube-prometheus-stack" `
    -Chart "prometheus-community/kube-prometheus-stack" `
    -Version $versionKubePrometheusStack `
    -ValuesFile "$valuesPath\kube-prometheus-stack.yaml"

Write-Host "Waiting for Prometheus CRDs..."
& kubectl wait --for=condition=Established crd/prometheuses.monitoring.coreos.com --timeout=120s
& kubectl wait --for=condition=Established crd/servicemonitors.monitoring.coreos.com --timeout=120s
& kubectl wait --for=condition=Established crd/alertmanagers.monitoring.coreos.com --timeout=120s

Wait-Rollout -Kind "deployment"  -Name "kube-prometheus-stack-grafana"
Wait-Rollout -Kind "deployment"  -Name "kube-prometheus-stack-kube-state-metrics"
Wait-Rollout -Kind "statefulset" -Name "prometheus-kube-prometheus-stack-prometheus"
Wait-Rollout -Kind "statefulset" -Name "alertmanager-kube-prometheus-stack-alertmanager"
Wait-Rollout -Kind "daemonset"   -Name "kube-prometheus-stack-prometheus-node-exporter"

#
# 5. Loki — log aggregation (single-binary mode)
#
Write-Host ""
Write-Host "=== Installing Loki ==="
Invoke-HelmInstall `
    -ReleaseName "loki" `
    -Chart "grafana/loki" `
    -Version $versionLoki `
    -ValuesFile "$valuesPath\loki.yaml"

Wait-Rollout -Kind "statefulset" -Name "loki"

#
# 6. Tempo — distributed traces (single-binary mode)
#
Write-Host ""
Write-Host "=== Installing Tempo ==="
Invoke-HelmInstall `
    -ReleaseName "tempo" `
    -Chart "grafana/tempo" `
    -Version $versionTempo `
    -ValuesFile "$valuesPath\tempo.yaml"

Wait-Rollout -Kind "statefulset" -Name "tempo"

#
# 7. Pushgateway — push-based metrics for short-lived batch pods
#
Write-Host ""
Write-Host "=== Installing Pushgateway ==="
Invoke-HelmInstall `
    -ReleaseName "prometheus-pushgateway" `
    -Chart "prometheus-community/prometheus-pushgateway" `
    -Version $versionPushgateway `
    -ValuesFile "$valuesPath\pushgateway.yaml"

Wait-Rollout -Kind "deployment" -Name "prometheus-pushgateway"

#
# 8. OpenTelemetry Collector — OTLP receiver, fans out to Prometheus / Loki / Tempo
#
Write-Host ""
Write-Host "=== Installing OpenTelemetry Collector ==="
Invoke-HelmInstall `
    -ReleaseName "opentelemetry-collector" `
    -Chart "open-telemetry/opentelemetry-collector" `
    -Version $versionOtelCollector `
    -ValuesFile "$valuesPath\opentelemetry-collector.yaml"

Wait-Rollout -Kind "deployment" -Name "opentelemetry-collector"

#
# 9. Promtail — DaemonSet that scrapes pod logs and ships to Loki
#
Write-Host ""
Write-Host "=== Installing Promtail ==="
Invoke-HelmInstall `
    -ReleaseName "promtail" `
    -Chart "grafana/promtail" `
    -Version $versionPromtail `
    -ValuesFile "$valuesPath\promtail.yaml"

Wait-Rollout -Kind "daemonset" -Name "promtail"

#
# 10. Self-test: verify all pods are Running
#
Write-Host ""
Write-Host "=== Self-test: pod health ==="
$pods = & kubectl get pods -n $namespace --no-headers
$pods | ForEach-Object { Write-Host $_ }

$notReady = $pods | Where-Object {
    $_ -notmatch '\s+Running\s+' -and $_ -notmatch '\s+Completed\s+'
}

if ($notReady) {
    Write-Host ""
    Write-Host "WARNING: The following pods are not Running:"
    $notReady | ForEach-Object { Write-Host "  $_" }
    Write-Host ""
    Write-Host "Stack may still start up. Check again with: kubectl get pods -n $namespace"
} else {
    Write-Host ""
    Write-Host "All pods are Running."
}

#
# 11. Port-forwards (background, hidden windows)
#
Write-Host ""
Write-Host "=== Starting port-forwards ==="

Start-PortForward -ServiceName "kube-prometheus-stack-prometheus"  -PortMapping "9090:9090"
Start-PortForward -ServiceName "kube-prometheus-stack-alertmanager" -PortMapping "9093:9093"
Start-PortForward -ServiceName "kube-prometheus-stack-grafana"      -PortMapping "3000:80"
Start-PortForward -ServiceName "prometheus-pushgateway"             -PortMapping "9091:9091"
Start-PortForward -ServiceName "loki"                               -PortMapping "3100:3100"
Start-PortForward -ServiceName "tempo"                              -PortMapping "3200:3200"
Start-PortForward -ServiceName "opentelemetry-collector"            -PortMapping "4317:4317 4318:4318"

#
# 12. Summary
#
Write-Host ""
Write-Host "========================================"
Write-Host "Monitoring stack installed successfully."
Write-Host "========================================"
Write-Host ""
Write-Host "Service                  URL"
Write-Host "------------------------ --------------------------------"
Write-Host "Grafana                  http://localhost:3000"
Write-Host "  Username:              admin"
Write-Host "  Password:              admin"
Write-Host "Prometheus               http://localhost:9090"
Write-Host "Alertmanager             http://localhost:9093"
Write-Host "Pushgateway              http://localhost:9091"
Write-Host "Loki                     http://localhost:3100"
Write-Host "Tempo                    http://localhost:3200"
Write-Host "OTel Collector (gRPC)    localhost:4317"
Write-Host "OTel Collector (HTTP)    http://localhost:4318"
Write-Host ""
Write-Host "Quick checks:"
Write-Host "  Prometheus targets:    http://localhost:9090/targets"
Write-Host "  Grafana datasources:   http://localhost:3000/connections/datasources"
Write-Host ""
Write-Host "Spring Boot app config (send OTLP to collector):"
Write-Host "  management.otlp.tracing.endpoint=http://localhost:4318/v1/traces"
Write-Host "  management.otlp.metrics.export.url=http://localhost:4318/v1/metrics"
Write-Host ""
