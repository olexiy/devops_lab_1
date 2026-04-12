$ErrorActionPreference = "Stop"

$namespace = "argocd"
$installUrl = "https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml"
$localPort = 8085

function Stop-ArgoCdPortForward {
    Write-Host "Cleaning old argocd port-forward processes..."
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq "kubectl.exe" -and
            $_.CommandLine -match "port-forward" -and
            $_.CommandLine -match "argocd-server"
        } |
        ForEach-Object {
            Write-Host "Stopping PID $($_.ProcessId)"
            Stop-Process -Id $_.ProcessId -Force
        }
}

function Wait-NamespaceDeleted {
    param([string]$Name, [int]$TimeoutSeconds = 240)

    $start = Get-Date
    while ($true) {
        kubectl get namespace $Name *> $null
        if ($LASTEXITCODE -ne 0) {
            return
        }

        if (((Get-Date) - $start).TotalSeconds -gt $TimeoutSeconds) {
            throw "Timeout waiting for namespace '$Name' to be deleted."
        }

        Start-Sleep -Seconds 3
    }
}

Write-Host "========================================"
Write-Host "Installing Argo CD"
Write-Host "Namespace: $namespace"
Write-Host "========================================"
Write-Host ""

#
# 1. Stop old local forwarding
#
Stop-ArgoCdPortForward

#
# 2. Hard reset old installation
#
Write-Host "Deleting old namespace '$namespace' if it exists..."
kubectl delete namespace $namespace --ignore-not-found=true

Write-Host "Waiting for namespace deletion..."
Wait-NamespaceDeleted -Name $namespace -TimeoutSeconds 240

#
# 3. Recreate namespace
#
Write-Host "Creating namespace '$namespace'..."
kubectl create namespace $namespace | Out-Null

#
# 4. Fresh install
#
Write-Host "Installing Argo CD with server-side apply..."
kubectl apply --server-side --force-conflicts -n $namespace -f $installUrl

#
# 5. Wait for CRDs
#
Write-Host "Waiting for CRDs to become available..."
kubectl wait --for=condition=Established crd/applications.argoproj.io --timeout=180s
kubectl wait --for=condition=Established crd/applicationsets.argoproj.io --timeout=180s
kubectl wait --for=condition=Established crd/appprojects.argoproj.io --timeout=180s

#
# 6. Wait for rollouts
#
Write-Host "Waiting for argocd-server rollout..."
kubectl rollout status deployment/argocd-server -n $namespace --timeout=300s

Write-Host "Waiting for argocd-repo-server rollout..."
kubectl rollout status deployment/argocd-repo-server -n $namespace --timeout=300s

Write-Host "Waiting for argocd-dex-server rollout..."
kubectl rollout status deployment/argocd-dex-server -n $namespace --timeout=300s

Write-Host "Waiting for argocd-redis rollout..."
kubectl rollout status deployment/argocd-redis -n $namespace --timeout=300s

Write-Host "Waiting for argocd-applicationset-controller rollout..."
kubectl rollout status deployment/argocd-applicationset-controller -n $namespace --timeout=300s

Write-Host "Waiting for argocd-notifications-controller rollout..."
kubectl rollout status deployment/argocd-notifications-controller -n $namespace --timeout=300s

Write-Host "Waiting for argocd-application-controller rollout..."
kubectl rollout status statefulset/argocd-application-controller -n $namespace --timeout=300s

#
# 7. Verify pods
#
Write-Host ""
Write-Host "Current Argo CD pods:"
kubectl get pods -n $namespace

#
# 8. Read initial admin password
#
Write-Host ""
Write-Host "Reading initial admin password..."
$argoPasswordBase64 = kubectl -n $namespace get secret argocd-initial-admin-secret -o jsonpath="{.data.password}"

if (-not $argoPasswordBase64) {
    throw "Could not read argocd-initial-admin-secret"
}

$argoPassword = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($argoPasswordBase64))

if (-not $argoPassword) {
    throw "Decoded Argo CD password is empty"
}

#
# 9. Start port-forward
#
Write-Host ""
Write-Host "Starting Argo CD UI port-forward in background on https://localhost:$localPort ..."

$process = Start-Process -FilePath "kubectl" `
    -ArgumentList "port-forward svc/argocd-server -n $namespace $localPort`:443" `
    -WindowStyle Hidden `
    -PassThru

Write-Host "Port-forward PID: $($process.Id)"

#
# 10. Final output
#
Write-Host ""
Write-Host "========================================"
Write-Host "Argo CD installed successfully."
Write-Host "========================================"
Write-Host ""
Write-Host "  URL:      https://localhost:$localPort"
Write-Host "  Username: admin"
Write-Host "  Password: $argoPassword"
Write-Host ""
Write-Host "  NOTE: Certificate warning in browser is expected in local development."
Write-Host "  After changing the admin password, the initial secret can be deleted."