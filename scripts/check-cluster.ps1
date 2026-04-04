function Show-NamespaceStatus {
    param([string]$Namespace)

    Write-Host ""
    Write-Host "=== $Namespace pods ==="
    $pods = & kubectl get pods -n $Namespace --no-headers 2>$null
    if (-not $pods) {
        Write-Host "  (no pods - not installed)"
        return
    }

    $pods | ForEach-Object { Write-Host "  $_" }

    $notReady = $pods | Where-Object {
        $_ -notmatch '\s+Running\s+' -and $_ -notmatch '\s+Completed\s+'
    }
    if ($notReady) {
        Write-Host ""
        Write-Host "  WARNING: some pods are not Running:"
        $notReady | ForEach-Object { Write-Host "    $_" }
    } else {
        Write-Host "  All pods Running."
    }
}

Write-Host "=== Current context ==="
kubectl config current-context

Write-Host ""
Write-Host "=== Cluster info ==="
kubectl cluster-info

Write-Host ""
Write-Host "=== Nodes ==="
kubectl get nodes -o wide

Write-Host ""
Write-Host "=== Namespaces ==="
kubectl get ns

Show-NamespaceStatus -Namespace "monitoring"
Show-NamespaceStatus -Namespace "argo"
Show-NamespaceStatus -Namespace "argocd"
Show-NamespaceStatus -Namespace "databases"
Show-NamespaceStatus -Namespace "batch"

Write-Host ""
Write-Host "=== Monitoring services ==="
& kubectl get svc -n monitoring 2>$null

Write-Host ""
Write-Host "=== Argo Workflows services ==="
& kubectl get svc -n argo 2>$null

Write-Host ""
Write-Host "=== Argo CD services ==="
& kubectl get svc -n argocd 2>$null

Write-Host ""
Write-Host "=== Database services ==="
& kubectl get svc -n databases 2>$null

Write-Host ""
Write-Host "========================================"
Write-Host "Installed component summary"
Write-Host "========================================"
Write-Host ""

$components = @(
    @{ Name = "Grafana";               Ns = "monitoring"; Svc = "kube-prometheus-stack-grafana";      Port = "3000"; Proto = "http"  },
    @{ Name = "Prometheus";            Ns = "monitoring"; Svc = "kube-prometheus-stack-prometheus";   Port = "9090"; Proto = "http"  },
    @{ Name = "Alertmanager";          Ns = "monitoring"; Svc = "kube-prometheus-stack-alertmanager"; Port = "9093"; Proto = "http"  },
    @{ Name = "Pushgateway";           Ns = "monitoring"; Svc = "prometheus-pushgateway";             Port = "9091"; Proto = "http"  },
    @{ Name = "Loki";                  Ns = "monitoring"; Svc = "loki";                              Port = "3100"; Proto = "http"  },
    @{ Name = "Tempo";                 Ns = "monitoring"; Svc = "tempo";                             Port = "3200"; Proto = "http"  },
    @{ Name = "OTel Collector (HTTP)"; Ns = "monitoring"; Svc = "opentelemetry-collector";           Port = "4318"; Proto = "http"  },
    @{ Name = "Argo Workflows";        Ns = "argo";       Svc = "argo-server";                       Port = "2746"; Proto = "https" },
    @{ Name = "Argo CD";               Ns = "argocd";     Svc = "argocd-server";                     Port = "8080"; Proto = "https" }
)

foreach ($c in $components) {
    $svcExists = & kubectl get svc $c.Svc -n $c.Ns --no-headers 2>$null
    if ($svcExists) {
        Write-Host ("  {0,-30} {1}://localhost:{2}" -f $c.Name, $c.Proto, $c.Port)
    } else {
        Write-Host ("  {0,-30} (not installed)" -f $c.Name)
    }
}
