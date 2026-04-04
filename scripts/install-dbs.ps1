$ErrorActionPreference = "Stop"

#
# CONFIGURATION
#
$sourceDbType = "mysql"       # mysql | postgres
$targetDbType = "postgres"    # mysql | postgres

$namespace = "databases"

$mysqlImage = "mysql:8.3"
$postgresImage = "postgres:16"

$dbUser = "appuser"
$dbPassword = "apppassword"
$dbName = "appdb"

$sourceDeploymentName = "source-db"
$targetDeploymentName = "target-db"

$enablePortForward = $true
$sourceLocalPort = 3307
$targetLocalPort = 5433

$prePullImages = $true

#
# HELPERS
#
function Invoke-Kubectl {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args
    )

    & kubectl @Args
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl failed: kubectl $($Args -join ' ')"
    }
}

function Invoke-KubectlYaml {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Yaml
    )

    $Yaml | & kubectl apply -f -
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl apply from YAML failed"
    }
}

function Invoke-Docker {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args
    )

    & docker @Args
    if ($LASTEXITCODE -ne 0) {
        throw "docker failed: docker $($Args -join ' ')"
    }
}

function Wait-NamespaceDeleted {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [int]$TimeoutSeconds = 240
    )

    $start = Get-Date

    while ($true) {
        # try/catch required: PS7 throws on non-zero exit when $ErrorActionPreference = "Stop"
        try { & kubectl get namespace $Name *> $null } catch {}

        if ($LASTEXITCODE -ne 0) {
            return
        }

        if (((Get-Date) - $start).TotalSeconds -gt $TimeoutSeconds) {
            throw "Timeout waiting for namespace '$Name' to be deleted."
        }

        Start-Sleep -Seconds 3
    }
}

function Assert-ResourceExists {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Kind,
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    & kubectl get $Kind $Name -n $namespace *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Expected resource not found: $Kind/$Name in namespace $namespace"
    }
}

function Get-DbClusterPort {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DbType
    )

    switch ($DbType.ToLower()) {
        "mysql"    { return 3306 }
        "postgres" { return 5432 }
        default    { throw "Unsupported DB type: $DbType" }
    }
}

function Get-DbConnectionString {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DbType,
        [Parameter(Mandatory = $true)]
        [string]$DbHost,
        [Parameter(Mandatory = $true)]
        [int]$Port
    )

    if ($DbType -eq "mysql") {
        return "mysql://${dbUser}:${dbPassword}@${DbHost}:${Port}/${dbName}"
    }

    if ($DbType -eq "postgres") {
        return "postgresql://${dbUser}:${dbPassword}@${DbHost}:${Port}/${dbName}"
    }

    throw "Unsupported DB type: $DbType"
}

function Get-RequiredImages {
    $images = New-Object System.Collections.Generic.List[string]

    if ($sourceDbType -eq "mysql" -or $targetDbType -eq "mysql") {
        $images.Add($mysqlImage)
    }

    if ($sourceDbType -eq "postgres" -or $targetDbType -eq "postgres") {
        $images.Add($postgresImage)
    }

    return $images | Select-Object -Unique
}

function PrePull-RequiredImages {
    if (-not $prePullImages) {
        Write-Host "Skipping docker pre-pull."
        return
    }

    $images = Get-RequiredImages

    Write-Host ""
    Write-Host "Pre-pulling required images..."

    foreach ($image in $images) {
        Write-Host "Pulling image: $image"
        Invoke-Docker -Args @("pull", $image)
    }
}

function Stop-PortForwardProcesses {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ServiceName
    )

    Write-Host "Cleaning old port-forward processes for $ServiceName ..."
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq "kubectl.exe" -and
            $_.CommandLine -match "port-forward" -and
            $_.CommandLine -match $ServiceName -and
            $_.CommandLine -match $namespace
        } |
        ForEach-Object {
            Write-Host "Stopping PID $($_.ProcessId)"
            Stop-Process -Id $_.ProcessId -Force
        }
}

function Show-DeploymentDiagnostics {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    Write-Host ""
    Write-Host "=== Diagnostics for deployment/$Name ==="

    Write-Host ""
    Write-Host "--- deployment describe ---"
    & kubectl describe deployment $Name -n $namespace

    Write-Host ""
    Write-Host "--- pods ---"
    & kubectl get pods -n $namespace -l "app=$Name" -o wide

    $podName = (& kubectl get pods -n $namespace -l "app=$Name" -o jsonpath="{.items[0].metadata.name}") 2>$null
    if ($LASTEXITCODE -eq 0 -and $podName) {
        Write-Host ""
        Write-Host "--- pod describe: $podName ---"
        & kubectl describe pod $podName -n $namespace

        Write-Host ""
        Write-Host "--- logs: $podName ---"
        & kubectl logs $podName -n $namespace --tail=200
    }
}

function Wait-Deployment {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    & kubectl rollout status "deployment/$Name" -n $namespace --timeout=420s
    if ($LASTEXITCODE -ne 0) {
        Show-DeploymentDiagnostics -Name $Name
        throw "kubectl failed: kubectl rollout status deployment/$Name -n $namespace --timeout=420s"
    }
}

function Start-PortForward {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ServiceName,
        [Parameter(Mandatory = $true)]
        [int]$LocalPort,
        [Parameter(Mandatory = $true)]
        [int]$RemotePort
    )

    Stop-PortForwardProcesses -ServiceName $ServiceName

    Write-Host "Starting port-forward for $ServiceName on localhost:$LocalPort -> $RemotePort ..."
    $process = Start-Process -FilePath "kubectl" `
        -ArgumentList "port-forward svc/$ServiceName -n $namespace $LocalPort`:$RemotePort" `
        -WindowStyle Hidden `
        -PassThru

    Write-Host "Port-forward PID for $ServiceName : $($process.Id)"
}

#
# INSTALLERS
#
function Install-MySQL {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    Write-Host "Installing MySQL: $Name"

    $yaml = @"
apiVersion: v1
kind: Secret
metadata:
  name: $Name-secret
  namespace: $namespace
type: Opaque
stringData:
  MYSQL_ROOT_PASSWORD: $dbPassword
  MYSQL_DATABASE: $dbName
  MYSQL_USER: $dbUser
  MYSQL_PASSWORD: $dbPassword
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: $Name
  namespace: $namespace
spec:
  replicas: 1
  selector:
    matchLabels:
      app: $Name
  template:
    metadata:
      labels:
        app: $Name
    spec:
      containers:
      - name: mysql
        image: $mysqlImage
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: 3306
        envFrom:
        - secretRef:
            name: $Name-secret
        startupProbe:
          tcpSocket:
            port: 3306
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 60
        readinessProbe:
          tcpSocket:
            port: 3306
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 12
        livenessProbe:
          tcpSocket:
            port: 3306
          periodSeconds: 10
          timeoutSeconds: 3
          failureThreshold: 6
---
apiVersion: v1
kind: Service
metadata:
  name: $Name
  namespace: $namespace
spec:
  selector:
    app: $Name
  ports:
  - name: mysql
    port: 3306
    targetPort: 3306
"@

    Invoke-KubectlYaml -Yaml $yaml
    Wait-Deployment -Name $Name

    Assert-ResourceExists -Kind "secret" -Name "$Name-secret"
    Assert-ResourceExists -Kind "deployment" -Name $Name
    Assert-ResourceExists -Kind "service" -Name $Name
}

function Install-Postgres {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    Write-Host "Installing PostgreSQL: $Name"

    $yaml = @"
apiVersion: v1
kind: Secret
metadata:
  name: $Name-secret
  namespace: $namespace
type: Opaque
stringData:
  POSTGRES_DB: $dbName
  POSTGRES_USER: $dbUser
  POSTGRES_PASSWORD: $dbPassword
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: $Name
  namespace: $namespace
spec:
  replicas: 1
  selector:
    matchLabels:
      app: $Name
  template:
    metadata:
      labels:
        app: $Name
    spec:
      containers:
      - name: postgres
        image: $postgresImage
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: 5432
        envFrom:
        - secretRef:
            name: $Name-secret
        startupProbe:
          tcpSocket:
            port: 5432
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 60
        readinessProbe:
          tcpSocket:
            port: 5432
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 12
        livenessProbe:
          tcpSocket:
            port: 5432
          periodSeconds: 10
          timeoutSeconds: 3
          failureThreshold: 6
---
apiVersion: v1
kind: Service
metadata:
  name: $Name
  namespace: $namespace
spec:
  selector:
    app: $Name
  ports:
  - name: postgres
    port: 5432
    targetPort: 5432
"@

    Invoke-KubectlYaml -Yaml $yaml
    Wait-Deployment -Name $Name

    Assert-ResourceExists -Kind "secret" -Name "$Name-secret"
    Assert-ResourceExists -Kind "deployment" -Name $Name
    Assert-ResourceExists -Kind "service" -Name $Name
}

function Install-DatabaseByType {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DbType,
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    switch ($DbType.ToLower()) {
        "mysql"    { Install-MySQL -Name $Name; break }
        "postgres" { Install-Postgres -Name $Name; break }
        default    { throw "Unsupported DB type: $DbType" }
    }
}

#
# MAIN
#
Write-Host "========================================"
Write-Host "Installing Databases"
Write-Host "Namespace: $namespace"
Write-Host "Source:    $sourceDbType"
Write-Host "Target:    $targetDbType"
Write-Host "========================================"
Write-Host ""

#
# 0. Pre-pull images
#
PrePull-RequiredImages

#
# 1. Hard reset namespace
#
Write-Host ""
Write-Host "Deleting old namespace '$namespace' if it exists..."
& kubectl delete namespace $namespace --ignore-not-found=true
if ($LASTEXITCODE -ne 0) {
    throw "kubectl failed while deleting namespace $namespace"
}

Write-Host "Waiting for namespace deletion..."
Wait-NamespaceDeleted -Name $namespace -TimeoutSeconds 240

Write-Host "Creating namespace '$namespace'..."
Invoke-Kubectl -Args @("create", "namespace", $namespace)

#
# 2. Install source and target
#
Install-DatabaseByType -DbType $sourceDbType -Name $sourceDeploymentName
Install-DatabaseByType -DbType $targetDbType -Name $targetDeploymentName

#
# 3. Port-forward
#
$sourceClusterPort = Get-DbClusterPort -DbType $sourceDbType
$targetClusterPort = Get-DbClusterPort -DbType $targetDbType

if ($enablePortForward) {
    Write-Host ""
    Start-PortForward -ServiceName $sourceDeploymentName -LocalPort $sourceLocalPort -RemotePort $sourceClusterPort
    Start-PortForward -ServiceName $targetDeploymentName -LocalPort $targetLocalPort -RemotePort $targetClusterPort
}

#
# 4. Final verification
#
Write-Host ""
Write-Host "Verifying deployments and services..."

Assert-ResourceExists -Kind "deployment" -Name $sourceDeploymentName
Assert-ResourceExists -Kind "service" -Name $sourceDeploymentName
Assert-ResourceExists -Kind "deployment" -Name $targetDeploymentName
Assert-ResourceExists -Kind "service" -Name $targetDeploymentName

Write-Host ""
Write-Host "Current pods:"
Invoke-Kubectl -Args @("get", "pods", "-n", $namespace, "-o", "wide")

Write-Host ""
Write-Host "Current services:"
Invoke-Kubectl -Args @("get", "svc", "-n", $namespace)

#
# 5. Output
#
$sourceInClusterHost = "${sourceDeploymentName}.${namespace}"
$targetInClusterHost = "${targetDeploymentName}.${namespace}"

$sourceClusterConnection = Get-DbConnectionString -DbType $sourceDbType -DbHost $sourceInClusterHost -Port $sourceClusterPort
$targetClusterConnection = Get-DbConnectionString -DbType $targetDbType -DbHost $targetInClusterHost -Port $targetClusterPort

$sourceLocalConnection = Get-DbConnectionString -DbType $sourceDbType -DbHost "localhost" -Port $sourceLocalPort
$targetLocalConnection = Get-DbConnectionString -DbType $targetDbType -DbHost "localhost" -Port $targetLocalPort

Write-Host ""
Write-Host "Databases installed successfully."
Write-Host ""

Write-Host "SOURCE DB"
Write-Host "  Type:            $sourceDbType"
Write-Host "  In-cluster host: $sourceInClusterHost"
Write-Host "  In-cluster port: $sourceClusterPort"
Write-Host "  Local host:      localhost"
Write-Host "  Local port:      $sourceLocalPort"
Write-Host "  Database:        $dbName"
Write-Host "  Username:        $dbUser"
Write-Host "  Password:        $dbPassword"
Write-Host "  Cluster URL:     $sourceClusterConnection"
Write-Host "  Local URL:       $sourceLocalConnection"
Write-Host ""

Write-Host "TARGET DB"
Write-Host "  Type:            $targetDbType"
Write-Host "  In-cluster host: $targetInClusterHost"
Write-Host "  In-cluster port: $targetClusterPort"
Write-Host "  Local host:      localhost"
Write-Host "  Local port:      $targetLocalPort"
Write-Host "  Database:        $dbName"
Write-Host "  Username:        $dbUser"
Write-Host "  Password:        $dbPassword"
Write-Host "  Cluster URL:     $targetClusterConnection"
Write-Host "  Local URL:       $targetLocalConnection"
Write-Host ""
Write-Host "NOTE: Databases have no web UI."
Write-Host "      Connect locally with a JDBC client, 'psql', or 'mysql' CLI using the URLs above."