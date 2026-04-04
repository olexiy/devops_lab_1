# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Two parallel workstreams sharing the same Kubernetes cluster and observability stack:

1. **Batch system** — nightly recalculation of customer reliability ratings (30–50 million records, ~2-hour SLA). Spring Batch + Argo Workflows.
2. **Microservices** — three Spring Boot headless REST services for customer account management. Primary learning vehicle for monitoring, distributed tracing, Spring Cloud, and GitOps.

**Current state**: Architectural design phase. Infrastructure scripts and configs exist; Java implementation is not yet written. The authoritative design reference is `architecture-plan-v2.md` (written in Russian).

## Version Policy

**Always web-search the current stable version** of any framework or library before writing or reviewing code. Never rely on memory for version numbers. Use only the latest stable release — no deprecated APIs.

## Local Environment Setup

Requires: Docker Desktop with Kubernetes enabled, `kubectl`, Helm 3.

```powershell
# 1. Create namespaces
kubectl apply -f infrastructure/namespaces.yaml

# 2. Verify cluster
./scripts/check-cluster.ps1

# 3. Install Argo Workflows (auth-mode=server, port 2746)
./scripts/install-argo-workflows.ps1

# 4. Install databases (MySQL on 3307, PostgreSQL on 5433)
./scripts/install-dbs.ps1

# 5. Install observability stack
./scripts/install-monitoring.ps1

# 6. Apply central tuning ConfigMap
kubectl apply -f config/batch-tuning.yaml

# 7. Submit batch workflow
argo submit -n batch workflow/templates/rating-workflow.yaml --watch
```

## Development Roadmap

1. **Infrastructure** — complete all install scripts, including full observability stack
2. **Microservices** — write customer / account / transaction services
3. **GitHub Actions** — CI pipelines (build, test, push image)
4. **Helm + Argo CD** — deploy services via GitOps
5. **Monitoring** — Micrometer instrumentation, Grafana dashboards, alerting, k6 load tests

## Architecture

### Batch Execution Pipeline

Orchestrated by Argo Workflows as a DAG:

```
CronWorkflow (daily 2 AM Berlin)
  → Init Pod       — Flyway validation, health checks, data cleanup
  → Partitioner    — NTILE-based scan → 50 JSON partition descriptors {minId, maxId, recordCount}
  → Worker Pods    — 50 partitions, max 6 concurrent; each runs a Spring Batch job
  → Report Pod     — Aggregated stats and notifications
```

### Batch Multi-Module Maven Monorepo (`batch-app/`)

Each module produces a separate Docker image with independent JVM tuning:

| Module | Role | JVM |
|---|---|---|
| `batch-core` | Shared entities, configs, utilities | — |
| `batch-init` | Flyway, health checks, cleanup | SerialGC |
| `batch-partitioner` | NTILE query, outputs partition JSON | SerialGC |
| `batch-worker` | Spring Batch Reader → Processor → Writer | ZGC |
| `batch-report` | Final report | SerialGC |

### Batch Worker Processing Flow

```
JdbcCursorItemReader (BETWEEN minId AND maxId from partition)
  → Processor:
      Query transactions (last 12 months)
      Query products (count, balance)
      HTTP /api/v1/external-score/{customerId}   ← CircuitBreaker (→ score-service)
      HTTP /api/v1/average-balance/{customerId}  ← CircuitBreaker (→ account-service)
      Calculate + classify rating (A/B/C…)
  → JdbcBatchItemWriter (UPSERT to PostgreSQL)
  → On failure: retry (3×) → skip → DLQ (max 100 skips/partition)
```

### Key Partitioning Design

Non-sequential customer IDs handled via NTILE window function — not simple min/max range splitting. Produces balanced `{minId, maxId, recordCount}` descriptors. Database-agnostic (MySQL 8.0+, PostgreSQL 8.4+, Oracle, MSSQL).

### Microservices (`services/`)

Three Spring Boot services with inter-service HTTP calls, so distributed traces span all three:

```
transaction-service → account-service → customer-service
```

| Service | Responsibility |
|---|---|
| `customer-service` | CRUD for customers |
| `account-service` | Bank accounts per customer; calls customer-service to validate customer |
| `transaction-service` | Transactions per account; calls account-service to validate account and update balance |

The account-service and transaction-service also serve as the real implementations of the HTTP endpoints that `batch-worker` calls (`/api/v1/average-balance`, `/api/v1/external-score`), replacing the original mock stubs.

### Databases

- **Source**: MySQL 8.3 (`customers`, `customer_transactions`, `customer_products`)
- **Target**: PostgreSQL 16 (`customer_ratings`, `rating_processing_dlq`, `rating_processing_progress`)
- **Job Repository**: PostgreSQL (same instance as target)
- UPSERT: `INSERT ON CONFLICT` for PostgreSQL, `INSERT ON DUPLICATE KEY UPDATE` for MySQL
- Flyway migrations: `services/flyway/mysql/` and `services/flyway/postgresql/`

### Central Batch Configuration

All tuning in `config/batch-tuning.yaml` (Kubernetes ConfigMap):

- `PARTITION_COUNT`: 50 / `MAX_PARALLEL_PODS`: 6
- `CHUNK_SIZE` / `FETCH_SIZE`: 500 / `VIRTUAL_THREADS`: 80 (per worker)
- `RETRY_LIMIT`: 3 / `SKIP_LIMIT`: 100
- HikariCP pool sizes for source, target, and job-repo connections
- `JVM_OPTS_WORKER` (ZGC) / `JVM_OPTS_INIT` (SerialGC)

### Observability Stack (`monitoring` namespace)

| Component | Role |
|---|---|
| Prometheus | Metrics scraping + storage |
| Alertmanager | Alert routing |
| Pushgateway | Push-based metrics from short-lived batch pods |
| Grafana | Dashboards — datasources: Prometheus, Loki, Tempo |
| Loki + Promtail | Log aggregation |
| Tempo | Distributed traces |
| OpenTelemetry Collector | Receives OTLP from apps, fans out to Prometheus / Loki / Tempo |
| kube-state-metrics | Kubernetes object metrics |
| node-exporter | Node-level infrastructure metrics |

Installed via `./scripts/install-monitoring.ps1`. Load testing with k6.

## Platform Notes

### Windows scripts
All scripts in `scripts/` are Windows PowerShell (`.ps1`).

### TODO — Mac equivalents
When first opening this project on macOS, create `scripts/mac/` with Bash (`.sh`) equivalents of all PowerShell scripts. Use `brew` for package installs, `open` for launching browser, and replace `Start-Process -WindowStyle Hidden` with `nohup ... &` for background port-forwards. Tools to install on Mac: `brew install kubectl helm python jq k6 maven` and download Argo CLI from GitHub releases (darwin-amd64 or darwin-arm64).
