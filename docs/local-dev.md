# Local Development — Running Services on the Host

Fastest way to manually test the three microservices without deploying to Kubernetes.

## Prerequisites

- Docker Desktop with Kubernetes enabled and running
- `kubectl` in PATH
- Java 21 (JDK)
- `install-dbs.ps1` was executed at least once (creates the `source-db` MySQL deployment)

---

## First-time setup

### 1. Create the microservice databases

Run once (or after `install-dbs.ps1` wipes the namespace):

```powershell
.\scripts\setup-microservice-dbs.ps1
```

This script:
- Scales up `source-db` (MySQL 8.3) in the `databases` namespace
- Creates `customers_db`, `accounts_db`, `transactions_db`
- Grants `appuser` full access to all three
- Starts a background port-forward `localhost:3307 → source-db:3306`

### 2. Verify the databases exist

```powershell
kubectl exec -n databases deploy/source-db -- mysql -uroot -papppassword -e "SHOW DATABASES;" 2>$null
```

Expected output includes `accounts_db`, `customers_db`, `transactions_db`.

---

## Running the services

Open **three separate terminals** from the project root:

```powershell
# Terminal 1 — customer-service  (port 8081)
cd services\customer-service
.\mvnw.cmd spring-boot:run

# Terminal 2 — account-service   (port 8082)
cd services\account-service
.\mvnw.cmd spring-boot:run

# Terminal 3 — transaction-service (port 8083)
cd services\transaction-service
.\mvnw.cmd spring-boot:run
```

**Start order matters**: customer-service must be up before account-service, and
account-service before transaction-service (Feign clients connect on startup probes).
In practice a few seconds delay is enough; circuit breakers tolerate brief unavailability.

Flyway applies schema migrations (`V1__create_*.sql`) automatically on first start.

---

## Quick smoke test (PowerShell)

```powershell
# Create a customer
$c = Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/v1/customers `
     -ContentType "application/json" `
     -Body '{"firstName":"Anna","lastName":"Müller","email":"anna@example.com","dateOfBirth":"1990-05-15","status":"ACTIVE"}'
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

## Credentials reference

| What              | Value                   |
|-------------------|-------------------------|
| MySQL host        | `localhost:3307`        |
| MySQL user        | `appuser`               |
| MySQL password    | `apppassword`           |
| customer-service  | `http://localhost:8081` |
| account-service   | `http://localhost:8082` |
| transaction-service | `http://localhost:8083` |

---

## Database teardown

The `winddown.ps1` script scales **all** deployments to 0 (saves RAM when not working).
The databases and schema are preserved — data survives restarts.

To bring everything back up including monitoring:

```powershell
.\scripts\startup.ps1
# then re-run if port-forward was lost:
.\scripts\setup-microservice-dbs.ps1
```

To wipe all data and start fresh:

```powershell
.\scripts\install-dbs.ps1          # recreates the namespace → erases all data
.\scripts\setup-microservice-dbs.ps1  # re-creates microservice databases
```

---

## Running integration tests

Tests use Testcontainers (spin up their own MySQL) — no external infrastructure needed:

```powershell
cd services\customer-service    ; .\mvnw.cmd test
cd services\account-service     ; .\mvnw.cmd test
cd services\transaction-service ; .\mvnw.cmd test
```
