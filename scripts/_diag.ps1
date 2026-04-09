Write-Host "=== Port 3307 (MySQL) ==="
Test-NetConnection localhost -Port 3307 -InformationLevel Quiet -WarningAction SilentlyContinue

Write-Host "=== Port 5433 (PostgreSQL) ==="
Test-NetConnection localhost -Port 5433 -InformationLevel Quiet -WarningAction SilentlyContinue

Write-Host "=== Docker Compose databases ==="
docker ps --filter "name=services-db" --filter "name=services-target-db" --format "{{.Names}}  {{.Status}}"

Write-Host "=== kubectl port-forward processes ==="
Get-CimInstance Win32_Process | Where-Object { $_.Name -eq "kubectl.exe" -and $_.CommandLine -match "port-forward" } | Select-Object ProcessId, CommandLine

Write-Host "=== application.yaml DB url ==="
Get-Content "services\customer-service\src\main\resources\application.yaml" | Select-String -Pattern "url|username|password|datasource" -CaseSensitive:$false
