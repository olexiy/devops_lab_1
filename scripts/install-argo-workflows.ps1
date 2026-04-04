$ErrorActionPreference = "Stop"

$namespace = "argo"
$argoWorkflowsVersion = "v4.0.3"
$installUrl = "https://github.com/argoproj/argo-workflows/releases/download/$argoWorkflowsVersion/install.yaml"
$localPort = 2746

function Stop-ArgoPortForward {
    Write-Host "Cleaning old argo-server port-forward processes..."
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq "kubectl.exe" -and
            $_.CommandLine -match "port-forward" -and
            $_.CommandLine -match "argo-server"
        } |
        ForEach-Object {
            Write-Host "Stopping PID $($_.ProcessId)"
            Stop-Process -Id $_.ProcessId -Force
        }
}

function Wait-NamespaceDeleted {
    param([string]$Name, [int]$TimeoutSeconds = 180)

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
Write-Host "Installing Argo Workflows"
Write-Host "Version:   $argoWorkflowsVersion"
Write-Host "Namespace: $namespace"
Write-Host "========================================"
Write-Host ""

#
# 1. Stop old local forwarding
#
Stop-ArgoPortForward

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
Write-Host "Installing Argo Workflows from official manifest..."
kubectl apply --server-side --force-conflicts -n $namespace -f $installUrl

#
# 5. Wait for CRDs
#
Write-Host "Waiting for CRDs..."
kubectl wait --for=condition=Established crd/workflows.argoproj.io --timeout=180s
kubectl wait --for=condition=Established crd/workflowtemplates.argoproj.io --timeout=180s
kubectl wait --for=condition=Established crd/cronworkflows.argoproj.io --timeout=180s
kubectl wait --for=condition=Established crd/clusterworkflowtemplates.argoproj.io --timeout=180s
kubectl wait --for=condition=Established crd/workfloweventbindings.argoproj.io --timeout=180s

#
# 6. Force ONLY server auth mode
#    Use strategic merge patch targeting the container by name.
#
Write-Host "Setting argo-server auth mode to ONLY 'server'..."
kubectl patch deployment argo-server -n $namespace --type='strategic' -p @"
spec:
  template:
    spec:
      containers:
      - name: argo-server
        args:
        - server
        - --auth-mode=server
"@

#
# 7. Restart argo-server explicitly to avoid stale pod template issues
#
Write-Host "Restarting argo-server..."
kubectl rollout restart deployment/argo-server -n $namespace

#
# 8. Wait for deployments
#
Write-Host "Waiting for workflow-controller rollout..."
kubectl rollout status deployment/workflow-controller -n $namespace --timeout=300s

Write-Host "Waiting for argo-server rollout..."
kubectl rollout status deployment/argo-server -n $namespace --timeout=300s

#
# 9. Verify actual args
#
Write-Host ""
Write-Host "Verifying argo-server container args..."
$actualArgs = kubectl get deployment argo-server -n $namespace -o jsonpath="{.spec.template.spec.containers[?(@.name=='argo-server')].args}"
Write-Host "argo-server args: $actualArgs"

if ($actualArgs -notmatch "--auth-mode=server") {
    throw "argo-server was not configured with --auth-mode=server"
}

if ($actualArgs -match "--auth-mode=client") {
    throw "argo-server still contains --auth-mode=client"
}

#
# 10. Show pods
#
Write-Host ""
Write-Host "Current Argo Workflows pods:"
kubectl get pods -n $namespace

#
# 11. Start port-forward
#
Write-Host ""
Write-Host "Starting port-forward on https://localhost:$localPort ..."
$process = Start-Process -FilePath "kubectl" `
    -ArgumentList "port-forward svc/argo-server -n $namespace $localPort`:2746" `
    -WindowStyle Hidden `
    -PassThru

Write-Host "Port-forward PID: $($process.Id)"

#
# 12. Final message
#
Start-Sleep -Seconds 3

Write-Host ""
Write-Host "========================================"
Write-Host "Argo Workflows installed successfully."
Write-Host "========================================"
Write-Host ""
Write-Host "  URL:       https://localhost:$localPort"
Write-Host "  Auth mode: server"
Write-Host "  Login:     No password required - click 'Skip Login' in the browser if prompted"
Write-Host ""
Write-Host "  NOTE: Certificate warning in browser is expected in local development."
Write-Host "  If the login page appears despite server auth mode, open an incognito window."