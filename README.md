# Batch Rating Project

Nightly recalculation of customer reliability ratings — 30–50 million records, ~2-hour SLA.

Two parallel workstreams:
- **Batch system** — Spring Batch + Argo Workflows, processes all customers nightly
- **Microservices** — three Spring Boot REST services for customer account management

Full architecture is documented in [`architecture-plan-v2.md`](architecture-plan-v2.md) (Russian).
Microservices architecture and Spring Cloud plan: [`docs/microservices-architecture.md`](docs/microservices-architecture.md).
Observability stack documentation: [`docs/monitoring-dashboards.md`](docs/monitoring-dashboards.md).
AI assistant instructions are in [`CLAUDE.md`](CLAUDE.md).

---

## Repository layout

```
batch-app/          Multi-module Maven monorepo (Java — not yet implemented)
services/           Spring Boot microservices (not yet implemented)
workflow/           Argo Workflows DAG templates (not yet implemented)
monitoring/         Grafana dashboards, Prometheus ServiceMonitors
  dashboards/       ConfigMap-based Grafana dashboard provisioning
infrastructure/
  namespaces.yaml   Kubernetes namespace definitions
  helm-values/      Helm values files for the observability stack
config/
  batch-tuning.yaml Central ConfigMap — all batch tuning parameters
scripts/            Windows PowerShell setup scripts (see below)
docs/
  microservices-architecture.md  Spring Cloud services plan (phases, versions, Initializr)
  monitoring-dashboards.md       Grafana/Prometheus stack documentation
  setup.md                       Local environment setup notes
```

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Docker Desktop | latest stable | docker.com — enable Kubernetes in settings |
| kubectl | bundled with Docker Desktop | — |
| Helm | 3.x | `winget install Helm.Helm` |
| Python | 3.13 | `winget install Python.Python.3.13` |
| Java JDK | 21 (Temurin) | pre-installed |
| Maven | 3.9.14 | installed to `C:\tools\apache-maven-3.9.14` |
| jq | 1.8.1 | `winget install jqlang.jq` |
| k6 | latest | `winget install GrafanaLabs.k6` |
| Argo CLI | v4.0.3 | installed to `C:\tools\argo.exe` |

---

## First-time setup

Run these steps in order. Each step is idempotent — running it again reinstalls the component.

```powershell
# 1. Create namespaces (batch, databases, monitoring, argo, argocd)
kubectl apply -f infrastructure/namespaces.yaml

# 2. Verify the cluster is healthy
./scripts/check-cluster.ps1

# 3. Install Argo Workflows (workflow orchestrator)
./scripts/install-argo-workflows.ps1

# 4. Install Argo CD (GitOps delivery — optional for early dev)
./scripts/install-argocd.ps1

# 5. Install source + target databases
./scripts/install-dbs.ps1

# 6. Install the full observability stack
./scripts/install-monitoring.ps1

# 7. Apply central batch tuning ConfigMap
kubectl apply -f config/batch-tuning.yaml
```

---

## Scripts reference

### `scripts/check-cluster.ps1`

**Purpose:** Diagnostic — shows the health of the cluster and all installed components.

**What it does:**
- Prints the current kubectl context, cluster info, and node status
- Shows pod status for every project namespace: `monitoring`, `argo`, `argocd`, `databases`, `batch`
- Lists services in each namespace
- Prints a summary table of installed components with their local URLs

**Run:**
```powershell
./scripts/check-cluster.ps1
```

**Output:** Pod status per namespace + summary table of URLs. No login info — this is read-only.

---

### `scripts/install-argo-workflows.ps1`

**Purpose:** Install Argo Workflows — the DAG orchestrator that runs the nightly batch job.

**What it reinstalls:**
- Deletes the `argo` namespace entirely and recreates it
- Applies the official Argo Workflows v4.0.3 manifest
- Patches `argo-server` to use `--auth-mode=server` (no client-side token required)
- Starts a background `kubectl port-forward` on localhost:2746

**Run:**
```powershell
./scripts/install-argo-workflows.ps1
```

**End of output:**
```
URL:       https://localhost:2746
Auth mode: server
Login:     No password required — click 'Skip Login' in the browser if prompted
```

**Notes:**
- Accept the self-signed certificate warning in the browser
- After submit: `C:\tools\argo.exe submit -n argo workflow/templates/rating-workflow.yaml --watch`

---

### `scripts/install-argocd.ps1`

**Purpose:** Install Argo CD — GitOps delivery controller (used in Phase 4 of the roadmap).

**What it reinstalls:**
- Deletes the `argocd` namespace entirely and recreates it
- Applies the official Argo CD stable manifest via server-side apply
- Waits for all 7 Argo CD deployments/statefulsets
- Reads the generated `admin` password from the Kubernetes secret
- Starts a background `kubectl port-forward` on localhost:8080

**Run:**
```powershell
./scripts/install-argocd.ps1
```

**End of output:**
```
URL:      https://localhost:8080
Username: admin
Password: <generated — shown in script output>
```

**Notes:**
- Accept the self-signed certificate warning (expected in local dev)
- Change the admin password after first login
- The initial secret `argocd-initial-admin-secret` can be deleted after password change

---

### `scripts/install-dbs.ps1`

**Purpose:** Install source database (MySQL 8.3) and target database (PostgreSQL 16).

**What it reinstalls:**
- Deletes the `databases` namespace entirely and recreates it
- Optionally pre-pulls Docker images
- Creates Kubernetes Deployments + Services + Secrets for both databases
- Includes startup, readiness, and liveness probes
- Starts background port-forwards for local access

**Configuration** (top of script):
| Variable | Default | Description |
|----------|---------|-------------|
| `$sourceDbType` | `mysql` | Source DB type (`mysql` or `postgres`) |
| `$targetDbType` | `postgres` | Target DB type |
| `$dbUser` | `appuser` | DB username |
| `$dbPassword` | `apppassword` | DB password |
| `$dbName` | `appdb` | Database name |
| `$sourceLocalPort` | `3307` | Local port for source DB |
| `$targetLocalPort` | `5433` | Local port for target DB |

**Run:**
```powershell
./scripts/install-dbs.ps1
```

**End of output:**
```
SOURCE DB  (MySQL)      Local URL: mysql://appuser:apppassword@localhost:3307/appdb
TARGET DB  (PostgreSQL) Local URL: postgresql://appuser:apppassword@localhost:5433/appdb

NOTE: Databases have no web UI.
      Connect locally with a JDBC client, 'psql', or 'mysql' CLI.
```

---

### `scripts/install-monitoring.ps1`

**Purpose:** Install the complete observability stack via Helm.

**What it reinstalls:**
- Uninstalls all existing Helm releases in the `monitoring` namespace
- Deletes and recreates the `monitoring` namespace (clean state)
- Installs all components in dependency order with health checks

**Components installed:**

| Component | Helm Chart | Version | Role |
|-----------|-----------|---------|------|
| kube-prometheus-stack | prometheus-community/kube-prometheus-stack | 82.16.1 | Prometheus + Alertmanager + Grafana + kube-state-metrics + node-exporter |
| Loki | grafana/loki | 6.55.0 | Log aggregation |
| Tempo | grafana/tempo | 1.24.4 | Distributed traces |
| Prometheus Pushgateway | prometheus-community/prometheus-pushgateway | 3.6.0 | Push-based metrics for short-lived batch pods |
| OpenTelemetry Collector | open-telemetry/opentelemetry-collector | 0.147.1 | OTLP ingestion, fans out to Prometheus / Loki / Tempo |
| Promtail | grafana/promtail | 6.17.1 | Log shipper (DaemonSet scraping pod logs → Loki) |

**Run:**
```powershell
./scripts/install-monitoring.ps1
```

**End of output:**
```
Component                URL                          Auth
------------------------ ---------------------------- -------------------
Grafana                  http://localhost:3000        admin / admin
Prometheus               http://localhost:9090        none
Alertmanager             http://localhost:9093        none
Pushgateway              http://localhost:9091        none
Loki                     http://localhost:3100        none
Tempo                    http://localhost:3200        none
OTel Collector (gRPC)    localhost:4317               none
OTel Collector (HTTP)    http://localhost:4318        none
```

**Grafana quick links after install:**
- Datasources: http://localhost:3000/connections/datasources
- Prometheus targets: http://localhost:9090/targets

**Spring Boot OTLP config** (for when services are implemented):
```yaml
management.otlp.tracing.endpoint: http://localhost:4318/v1/traces
management.otlp.metrics.export.url: http://localhost:4318/v1/metrics
```

---

## Development roadmap

| Phase | Status | Description |
|-------|--------|-------------|
| 1. Infrastructure | **Done** | All install scripts, Kubernetes configs |
| 2. Observability | **Done** | Prometheus, Grafana, Loki, Tempo, OTel Collector, 6 dashboards |
| 3. Microservices MVP | Next | config-server + customer / account / transaction services (Spring Boot 4, Spring Cloud 2025.1) |
| 4. Observability for services | Planned | Micrometer instrumentation, distributed tracing, service Grafana dashboards |
| 5. API Gateway | Planned | Spring Cloud Gateway, rate limiting, circuit breakers at edge |
| 6. CI/CD | Planned | GitHub Actions (path-based per service) + Argo CD GitOps |
| 7. Batch system | Planned | Spring Batch worker, Argo Workflows DAG, Pushgateway metrics |

Architecture decisions for Phase 3: [`docs/microservices-architecture.md`](docs/microservices-architecture.md).
See `CLAUDE.md` for full architecture details.

---

## Platform notes

### Windows scripts
All scripts in `scripts/` are Windows PowerShell (`.ps1`).

### TODO — Mac equivalents
When first opening this project on macOS, create `scripts/mac/` with Bash (`.sh`) equivalents of all PowerShell scripts. Use `brew` for package installs, `open` for launching the browser, and replace `Start-Process -WindowStyle Hidden` with `nohup ... &` for background port-forwards.
