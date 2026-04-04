$ErrorActionPreference = "Stop"

#
# CONFIGURATION
#
$namespaces = @("databases", "argo", "argocd", "monitoring")

#
# HELPERS
#
function Stop-AllPortForwards {
    Write-Host "Stopping all kubectl port-forward processes..."

    $procs = Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq "kubectl.exe" -and
            $_.CommandLine -match "port-forward"
        }

    if ($procs) {
        foreach ($p in $procs) {
            Write-Host "  Stopping PID $($p.ProcessId)"
            Stop-Process -Id $p.ProcessId -Force
        }
        Write-Host "  Stopped $($procs.Count) process(es)."
    } else {
        Write-Host "  No port-forward processes found."
    }
}

function Scale-Namespace {
    param(
        [string]$Namespace,
        [int]$Replicas
    )

    $exists = $false
    try {
        kubectl get namespace $Namespace *> $null
        $exists = ($LASTEXITCODE -eq 0)
    } catch { $exists = $false }

    if (-not $exists) {
        Write-Host "  Namespace '$Namespace' not found - skipping."
        return
    }

    # For monitoring: kill the Prometheus Operator first so it cannot
    # reconcile StatefulSets back to 1 before they are scaled down.
    if ($Namespace -eq "monitoring") {
        Write-Host "  Stopping kube-prometheus-stack-operator first ..."
        try {
            kubectl scale deployment kube-prometheus-stack-operator `
                -n $Namespace --replicas=0 2>&1 | Out-Null
        } catch {}
        # Brief pause to let the operator pod terminate
        Start-Sleep -Seconds 4
    }

    Write-Host "  Scaling deployments in '$Namespace' to $Replicas ..."
    try {
        kubectl scale deployment --all -n $Namespace --replicas=$Replicas 2>&1 |
            Where-Object { $_ -notmatch "no objects passed" } |
            ForEach-Object { Write-Host "    $_" }
    } catch { Write-Host "    (no deployments)" }

    Write-Host "  Scaling statefulsets in '$Namespace' to $Replicas ..."
    try {
        kubectl scale statefulset --all -n $Namespace --replicas=$Replicas 2>&1 |
            Where-Object { $_ -notmatch "no objects passed" } |
            ForEach-Object { Write-Host "    $_" }
    } catch { Write-Host "    (no statefulsets)" }
}

#
# MAIN
#
Write-Host "========================================"
Write-Host "Infrastructure Winddown"
Write-Host "========================================"
Write-Host ""
Write-Host "Scales all workloads to 0 without deleting anything."
Write-Host "Run startup.ps1 to bring everything back."
Write-Host ""

#
# 1. Kill port-forwards
#
Write-Host "=== Port-forwards ==="
Stop-AllPortForwards

#
# 2. Scale down each namespace
#
foreach ($ns in $namespaces) {
    Write-Host ""
    Write-Host "=== Namespace: $ns ==="
    Scale-Namespace -Namespace $ns -Replicas 0
}

#
# 3. Summary
#
Write-Host ""
Write-Host "========================================"
Write-Host "Winddown complete."
Write-Host "========================================"
Write-Host ""
Write-Host "All workloads scaled to 0. Port-forwards stopped."
Write-Host "Data preserved - PersistentVolumes and Secrets are untouched."
Write-Host "DaemonSets (node-exporter, promtail) remain running (cannot scale to 0)."
Write-Host ""
Write-Host "To restore: run startup.ps1"
Write-Host ""
