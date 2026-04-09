# Local Development — Running Services on the Host

Fastest way to manually test the three microservices without deploying to Kubernetes.

## Prerequisites

- Docker Desktop with Kubernetes enabled and running
- `kubectl` in PATH
- Java 21 (JDK)

---

## Daily workflow

### 1. Start infrastructure

```powershell
.\scripts\startup.ps1
```

This script:
- Starts Docker Desktop (if not running) and waits for Kubernetes
- Starts **MySQL 8.3** and **PostgreSQL 16** via Docker Compose (ports 3307, 5433)
- Scales up Kubernetes workloads (monitoring, Argo, Argo CD)
- Starts port-forwards for Grafana, Prometheus, Argo, etc.

Databases are created automatically on first start:
- MySQL: `customers_db`, `accounts_db`, `transactions_db` (via `docker/mysql-init.sql`)
- PostgreSQL: `appdb` (batch target, via `POSTGRES_DB` env var)

### 2. Start microservices

```powershell
.\scripts\start-services.ps1
```

This script:
- Verifies MySQL is reachable on `localhost:3307`
- Starts all three services as hidden background processes
- Polls `/actuator/health` until all three are UP (timeout 150 s)
- Logs go to `services/<name>/target/service.log` and `service-err.log`

**Start order** (hardcoded via 3 s stagger): customer-service -> account-service -> transaction-service.
Flyway applies schema migrations automatically on first start.

### 3. Shut down everything

```powershell
.\scripts\winddown.ps1
```

This script:
- Kills port-forwards and microservice Java processes
- Stops databases via `docker compose down`
- Scales Kubernetes workloads to 0
- Stops Docker Desktop and WSL2 VMs

Data is preserved across restarts (Docker volumes + Kubernetes PersistentVolumes).

---

## Quick smoke test (PowerShell)

```powershell
# Create a customer
$c = Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/v1/customers `
     -ContentType "application/json" `
     -Body '{"firstName":"Anna","lastName":"Mueller","email":"anna@example.com","dateOfBirth":"1990-05-15","status":"ACTIVE"}'
Write-Host "Customer id: $($c.id)"

# Open an account for that customer
$a = Invoke-RestMethod -Method Post -Uri http://localhost:8082/api/v1/accounts `
     -ContentType "application/json" `
     -Body "{`"customerId`":$($c.id),`"accountType`":`"CHECKING`",`"currency`":`"EUR`",`"openDate`":`"2026-04-08`"}"
Write-Host "Account id: $($a.id)"

# Credit the account
$t = Invoke-RestMethod -Method Post -Uri http://localhost:8083/api/v1/transactions `
     -ContentType "application/json" `
     -Body "{`"accountId`":$($a.id),`"customerId`":$($c.id),`"transactionType`":`"CREDIT`",`"amount`":1000,`"currency`":`"EUR`",`"transactionDate`":`"2026-04-08T10:00:00Z`"}"
Write-Host "Transaction balanceAfter: $($t.balanceAfter)"

# Check external score (batch endpoint)
Invoke-RestMethod http://localhost:8083/api/v1/external-score/$($c.id)

# Check average balance (batch endpoint)
Invoke-RestMethod http://localhost:8082/api/v1/average-balance/$($c.id)
```

---

## Alternative: Docker Compose for the full stack

If you want to run services inside Docker too (no `mvnw` needed), build JARs first:

```powershell
cd services\customer-service    ; .\mvnw.cmd package -DskipTests ; cd ..\..
cd services\account-service     ; .\mvnw.cmd package -DskipTests ; cd ..\..
cd services\transaction-service ; .\mvnw.cmd package -DskipTests ; cd ..\..
```

Then start everything:

```powershell
cd services
docker compose up -d
```

Services discover each other by container name (`http://customer-service:8081`, etc.).

---

## Credentials reference

| What                | Value                   |
|---------------------|-------------------------|
| MySQL host          | `localhost:3307`        |
| PostgreSQL host     | `localhost:5433`        |
| DB user             | `appuser`               |
| DB password         | `apppassword`           |
| customer-service    | `http://localhost:8081` |
| account-service     | `http://localhost:8082` |
| transaction-service | `http://localhost:8083` |

---

## Viewing service logs

```powershell
# Tail a service log
Get-Content services\customer-service\target\service.log -Tail 50 -Wait
```

---

## Running integration tests

Tests use Testcontainers (spin up their own MySQL) — no external infrastructure needed:

```powershell
cd services\customer-service    ; .\mvnw.cmd test
cd services\account-service     ; .\mvnw.cmd test
cd services\transaction-service ; .\mvnw.cmd test
```

---

## Database teardown

To wipe all data and start fresh:

```powershell
cd services
docker compose down -v    # removes volumes — erases all data
docker compose up -d db target-db   # recreates empty databases
```
