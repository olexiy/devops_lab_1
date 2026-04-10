# Architecture Plan: Customer Reliability Rating — Batch Processing & Microservices

## Project Summary

**Goal:** Nightly recalculation of customer reliability ratings for 30-50 million records.  
**Source:** MySQL 8.3 (three databases owned by microservices)  
**Target:** PostgreSQL 16 (ratings, DLQ, progress tracking)  
**SLA:** Target ~2 hours, but reliability and optimization take priority over a hard limit  
**Stack:** Spring Batch 6.0, Spring Boot 4.0, Spring Framework 7, Java 21  
**Orchestration:** Argo Workflows (installed from scratch)  
**Infrastructure:** Kubernetes (on-premise, Helm Charts only, no external cloud services)  
**Automation:** PowerShell scripts for installation and redeployment of all infrastructure  
**Approach:** All performance-affecting parameters centralized in a single configuration file

**Current state (April 2025):**
- `customer-service` — MVP complete, 16 integration tests green
- `account-service` — MVP complete, 13 integration tests green
- `transaction-service` — MVP complete, 15 integration tests written (not yet run)
- `rating-service` — not started
- `gateway` — not started
- `frontend` — not started
- `batch-app` — directory structure exists, no code yet

---

## Part 0: Key Architectural Decisions

### 0.1 Partitioning with Non-Sequential IDs

Problem: in a banking system, customer IDs are not necessarily sequential — they could be UUIDs, hashes, or have gaps (deleted records). The classic `ColumnRangePartitioner` with `MIN(id)`/`MAX(id)` creates unbalanced partitions.

**Solution: NTILE-based pre-scan**

At the partitioner stage (separate pod in Argo), execute:

```sql
-- Database-agnostic: NTILE supported in MySQL 8+, PostgreSQL 8.4+, Oracle 8i+, MS SQL 2005+
SELECT partition_num, MIN(id) AS min_id, MAX(id) AS max_id, COUNT(*) AS record_count
FROM (
    SELECT id, NTILE(:partitionCount) OVER (ORDER BY id) AS partition_num
    FROM customers
) partitioned
GROUP BY partition_num
ORDER BY partition_num;
```

This query:
- Sorts ALL records by ID (even non-sequential ones)
- Divides them into N equal groups (NTILE guarantees +/-1 record difference between groups)
- Returns `min_id`, `max_id`, and `record_count` for each group
- `:partitionCount` is a configuration parameter

**Performance:** For 50M records — 30-90 seconds (full table scan with PRIMARY KEY sort). Executed once before launching workers.

Output: JSON array used by Argo Workflows for fan-out.

### 0.2 Centralized Tuning Parameters

All tuneable parameters in one place — Kubernetes ConfigMap + CronWorkflow parameters:

```yaml
# === config/batch-tuning.yaml — single optimization config ===
apiVersion: v1
kind: ConfigMap
metadata:
  name: batch-tuning
  namespace: batch
data:
  # --- Partitioning ---
  PARTITION_COUNT: "50"

  # --- Parallelism (Argo level) ---
  MAX_PARALLEL_PODS: "6"

  # --- Spring Batch parameters ---
  CHUNK_SIZE: "500"
  FETCH_SIZE: "500"
  VIRTUAL_THREADS: "80"

  # --- Retry/Skip ---
  RETRY_LIMIT: "3"
  SKIP_LIMIT: "100"

  # --- Connection pools ---
  SOURCE_DB_POOL_SIZE: "5"
  TARGET_DB_POOL_SIZE: "5"
  JOBREPOSITORY_DB_POOL_SIZE: "3"

  # --- Web Service ---
  WEBSERVICE_CONNECT_TIMEOUT_MS: "2000"
  WEBSERVICE_READ_TIMEOUT_MS: "5000"

  # --- JVM parameters (worker pods) ---
  WORKER_JVM_OPTS: >-
    -XX:+UseZGC -XX:+ZGenerational
    -Xms1g -Xmx1536m -XX:+UseStringDeduplication

  # --- JVM parameters (init/partitioner/report pods) ---
  LIGHTWEIGHT_JVM_OPTS: >-
    -XX:+UseSerialGC -Xms256m -Xmx512m

  # --- Argo retry ---
  WORKER_RETRY_LIMIT: "2"
  WORKER_RETRY_BACKOFF: "30s"

  # --- Monitoring ---
  METRICS_PUSH_INTERVAL: "10s"
  PROGRESS_UPDATE_INTERVAL: "10"
```

To apply changes: `kubectl apply -f config/batch-tuning.yaml` -> restart workflow -> new values picked up.

### 0.3 Orchestration: Why Argo Workflows

**Why not KEDA+Kafka:**
- KEDA+Kafka introduces two components (Kafka cluster + KEDA operator), each requiring Helm installation, configuration, monitoring, and maintenance
- Argo Workflows — one Helm chart providing: DAG, visualization, retry, parallelism control, CronWorkflow, scale-to-zero out of the box
- `parallelism: N` in Argo automatically limits concurrent pods and queues the rest — exactly the behavior needed
- After workflow completion — no pods remain (scale-to-zero by design)

### 0.4 Application Architecture: Monorepo with Separate Modules

**Decision: Multi-module Maven Monorepo, separate Docker image per module.**

Rationale:
- Init pod and Worker pod have completely different dependencies. Worker pulls WebClient, Resilience4j, full Spring Batch chunk processing. Init pulls Flyway, healthcheck clients. A single fat JAR = all dependencies in all pods, unnecessary image size, unnecessary startup time.
- JVM startup tuning is fundamentally different: worker needs ZGC, large heap, virtual threads. Init — SerialGC, minimal heap, fast start.
- In a single module with profiles, there's always temptation to reuse classes inappropriately — after six months the separation erodes.
- Container image: worker ~200MB, init ~120MB. Smaller image = faster pull.

```
batch-app/
├── pom.xml                    (parent POM)
├── batch-core/                <- Domain: entities, DTOs, shared utilities, DB config interfaces
│   └── src/main/java/
│       └── com/bank/rating/core/
│           ├── entity/        (Customer, CustomerRating, etc.)
│           ├── config/        (DataSource configs, shared properties)
│           └── util/          (ID partitioning utilities)
├── batch-init/                <- Init pod: Flyway, healthchecks, cleanup, startup report
│   ├── src/main/java/
│   ├── Dockerfile
│   └── pom.xml                (depends on batch-core)
├── batch-partitioner/         <- Partitioner pod: NTILE query -> JSON stdout
│   ├── src/main/java/
│   ├── Dockerfile
│   └── pom.xml                (depends on batch-core)
├── batch-worker/              <- Worker pod: Spring Batch job, reader/processor/writer
│   ├── src/main/java/
│   ├── Dockerfile
│   └── pom.xml                (depends on batch-core)
└── batch-report/              <- Report pod: final report
    ├── src/main/java/
    ├── Dockerfile
    └── pom.xml                (depends on batch-core)
```

Each module — separate Spring Boot application with its own `main()`, `application.yaml`, `Dockerfile`. Shared code — only in `batch-core`.

### 0.5 Database-Agnostic Approach

- Reader: standard `JdbcCursorItemReader` with ANSI SQL (`SELECT ... WHERE id BETWEEN ? AND ?`)
- Writer: UPSERT via Spring Batch `JdbcBatchItemWriter`. SQL for UPSERT — in `application.yaml` as a property, different per DBMS:
  - PostgreSQL: `INSERT ... ON CONFLICT (customer_id) DO UPDATE SET ...`
  - MySQL: `INSERT ... ON DUPLICATE KEY UPDATE ...`
  - Oracle: `MERGE INTO ... USING ... WHEN MATCHED THEN UPDATE WHEN NOT MATCHED THEN INSERT`
- Flyway: separate migration directories per DBMS (`db/migration/mysql/`, `db/migration/postgresql/`)
- Partitioner NTILE: standard SQL window function, supported everywhere
- JobRepository: Spring Batch auto-detects dialect from DataSource
- Connection pool: HikariCP with parameters from ConfigMap

---

## Part 1: Infrastructure Setup

### 1.1 Project Structure

```
batch-rating-project/
├── config/
│   └── batch-tuning.yaml            # Single optimization config (ConfigMap)
├── infrastructure/
│   ├── helm-values/
│   │   ├── kube-prometheus-stack.yaml
│   │   ├── loki.yaml
│   │   ├── tempo.yaml
│   │   ├── opentelemetry-collector.yaml
│   │   ├── pushgateway.yaml
│   │   ├── promtail.yaml
│   │   ├── mysql-exporter.yaml
│   │   └── postgres-exporter.yaml
│   ├── grafana-dashboards/
│   ├── k3d/
│   │   └── cluster-config.yaml
│   └── namespaces.yaml
├── scripts/                          # PowerShell (.ps1) install scripts
│   ├── check-cluster.ps1
│   ├── install-argo-workflows.ps1
│   ├── install-argocd.ps1
│   ├── install-dbs.ps1
│   ├── install-monitoring.ps1
│   ├── setup-microservice-dbs.ps1
│   ├── start-services.ps1
│   ├── startup.ps1
│   └── winddown.ps1
├── monitoring/
│   └── dashboards/
├── services/
│   ├── docker-compose.yml            # Local dev: MySQL 8.3 + PostgreSQL 16 + 3 services
│   ├── docker/
│   │   └── mysql-init.sql
│   ├── customer-service/             # Port 8081, MySQL customers_db
│   ├── account-service/              # Port 8082, MySQL accounts_db
│   ├── transaction-service/          # Port 8083, MySQL transactions_db
│   ├── rating-service/               # Port 8084, PostgreSQL appdb (read-only) — PLANNED
│   ├── gateway/                      # Port 8080, Spring Cloud Gateway — PLANNED
│   └── data-generator/              # Python bulk data generator — PLANNED
├── frontend/                         # Vite + React + Tailwind dashboard — PLANNED
├── batch-app/                        # Multi-module Maven monorepo
│   ├── pom.xml (parent POM)
│   ├── batch-core/
│   ├── batch-init/
│   ├── batch-partitioner/
│   ├── batch-worker/
│   └── batch-report/
├── workflow/
│   ├── templates/
│   │   ├── cron-workflow.yaml
│   │   └── rating-workflow.yaml
│   └── rbac/
│       └── workflow-rbac.yaml
└── docs/
```

### 1.2 Helm Values

**Argo Workflows (`helm-values/argo-workflows.yaml`):**
```yaml
server:
  enabled: true
  serviceType: ClusterIP
  extraArgs:
    - --auth-mode=server
controller:
  metricsConfig:
    enabled: true
    port: 9090
    path: /metrics
  workflowDefaults:
    spec:
      ttlStrategy:
        secondsAfterCompletion: 172800  # 48 hours
      podGC:
        strategy: OnWorkflowCompletion
```

**OpenTelemetry Collector (`helm-values/otel-collector.yaml`):**
```yaml
mode: daemonset
config:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317
        http:
          endpoint: 0.0.0.0:4318
  processors:
    batch:
      timeout: 10s
    k8sattributes:
      extract:
        metadata: [k8s.pod.name, k8s.namespace.name, k8s.node.name]
  exporters:
    prometheusremotewrite:
      endpoint: "http://monitoring-prometheus-server.monitoring:9090/api/v1/write"
  service:
    pipelines:
      metrics:
        receivers: [otlp]
        processors: [batch, k8sattributes]
        exporters: [prometheusremotewrite]
```

---

## Part 2: Services and Data

### 2.1 Database Schemas (actual, owned by microservices)

**customers_db (MySQL, owned by customer-service):**

```sql
CREATE TABLE customers (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    phone         VARCHAR(30),
    date_of_birth DATE         NOT NULL,
    status        ENUM ('ACTIVE','INACTIVE','BLOCKED','CLOSED') NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_customers_email (email),
    INDEX idx_customers_status (status),
    INDEX idx_customers_last_name (last_name)
);
```

**accounts_db (MySQL, owned by account-service):**

```sql
CREATE TABLE accounts (
    id             BIGINT                                              NOT NULL AUTO_INCREMENT,
    account_number VARCHAR(20)                                         NOT NULL,
    customer_id    BIGINT                                              NOT NULL,
    account_type   ENUM ('CHECKING', 'SAVINGS', 'CREDIT', 'DEPOSIT')  NOT NULL,
    status         ENUM ('ACTIVE', 'FROZEN', 'CLOSED')                NOT NULL DEFAULT 'ACTIVE',
    currency       CHAR(3)                                             NOT NULL DEFAULT 'EUR',
    balance        DECIMAL(19, 4)                                      NOT NULL DEFAULT 0.0000,
    credit_limit   DECIMAL(19, 4)                                      NULL,
    open_date      DATE                                                NOT NULL,
    close_date     DATE                                                NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_accounts_account_number (account_number),
    INDEX idx_accounts_customer_id (customer_id),
    INDEX idx_accounts_customer_type (customer_id, account_type)
);
```

**transactions_db (MySQL, owned by transaction-service):**

```sql
CREATE TABLE transactions (
    id               BIGINT    NOT NULL AUTO_INCREMENT,
    reference_number CHAR(36)  NOT NULL,
    account_id       BIGINT    NOT NULL,
    customer_id      BIGINT    NOT NULL,
    transaction_type ENUM ('CREDIT', 'DEBIT', 'TRANSFER_IN', 'TRANSFER_OUT', 'FEE') NOT NULL,
    amount           DECIMAL(19, 4)  NOT NULL,
    currency         CHAR(3)         NOT NULL DEFAULT 'EUR',
    balance_after    DECIMAL(19, 4)  NOT NULL,
    description      VARCHAR(500)    NULL,
    transaction_date DATETIME(3)     NOT NULL,
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_transactions_reference_number (reference_number),
    INDEX idx_transactions_account_id (account_id),
    INDEX idx_transactions_customer_id (customer_id),
    INDEX idx_transactions_customer_date (customer_id, transaction_date),
    INDEX idx_transactions_account_date (account_id, transaction_date)
);
```

**appdb (PostgreSQL, owned by batch + rating-service reads):**

```sql
CREATE TABLE customer_ratings (
    customer_id           BIGINT PRIMARY KEY,
    rating_score          DECIMAL(5,2) NOT NULL,
    rating_class          VARCHAR(5) NOT NULL,
    risk_level            VARCHAR(20) NOT NULL,
    calculated_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    calculation_version   VARCHAR(10) NOT NULL,
    avg_balance_12m       DECIMAL(15,2),
    product_count         INT,
    transaction_volume_12m DECIMAL(15,2),
    external_score        DECIMAL(5,2),
    processing_pod        VARCHAR(100),
    processing_duration_ms INT
);

CREATE TABLE rating_processing_dlq (
    id             BIGSERIAL PRIMARY KEY,
    customer_id    BIGINT NOT NULL,
    error_message  TEXT,
    error_class    VARCHAR(200),
    retry_count    INT DEFAULT 0,
    failed_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    partition_id   INT,
    pod_name       VARCHAR(100)
);

CREATE TABLE rating_processing_progress (
    partition_id      INT PRIMARY KEY,
    total_records     INT NOT NULL,
    processed_records INT DEFAULT 0,
    skipped_records   INT DEFAULT 0,
    failed_records    INT DEFAULT 0,
    started_at        TIMESTAMP,
    completed_at      TIMESTAMP,
    pod_name          VARCHAR(100),
    status            VARCHAR(20) DEFAULT 'PENDING'
);
```

### 2.2 Test Data Generator

**Tool:** Python script  
**Location:** `services/data-generator/generate.py`  
**Usage:** `python generate.py --accounts 1000000`

The `--accounts` parameter specifies the number of account records. Data distribution:
- **Customers:** each has 1-3 accounts (weighted: 70% have 1, 20% have 2, 10% have 3). So ~1M accounts -> ~600K-700K customers.
- **Transactions per account:** 1-120 random, spread over the last 4 months.
- **Realistic data:** names from predefined arrays, random balances, transaction types matching the ENUM values.

Approach:
- Generate CSV files, then `LOAD DATA LOCAL INFILE` for maximum speed
- Progress bar (tqdm)
- Clears existing data first (TRUNCATE)
- Connects to MySQL via `mysql-connector-python`
- Config: host/port/db from environment variables or defaults matching `docker-compose.yml`

---

## Part 3: Spring Batch — Multi-Module Monorepo

### 3.1 Module Structure

```
batch-app/
├── pom.xml                    (parent POM)
├── batch-core/                <- Domain and shared components
│   └── com/bank/rating/core/
│       ├── entity/            Customer, CustomerRating, ProcessingProgress
│       ├── config/            DataSourceConfig, TuningProperties (from ConfigMap)
│       └── util/              PartitionRange DTO
├── batch-init/                <- Init pod
│   └── com/bank/rating/init/
│       ├── InitApplication.java
│       ├── FlywayValidator.java
│       ├── HealthChecker.java (checks MySQL, PostgreSQL, services)
│       └── DataCleaner.java   (table cleanup + create progress records)
├── batch-partitioner/         <- Partitioner pod
│   └── com/bank/rating/partitioner/
│       ├── PartitionerApplication.java
│       └── NtilePartitioner.java (NTILE query -> JSON stdout)
├── batch-worker/              <- Worker pod (main business logic)
│   └── com/bank/rating/worker/
│       ├── RatingWorkerApplication.java
│       ├── config/
│       │   ├── BatchJobConfig.java
│       │   ├── StepConfig.java
│       │   └── ResilienceConfig.java
│       ├── reader/
│       │   └── CustomerItemReader.java (JdbcCursorItemReader)
│       ├── processor/
│       │   └── RatingProcessor.java (web services + calculation)
│       ├── writer/
│       │   └── RatingItemWriter.java (UPSERT)
│       └── listener/
│           ├── ProgressListener.java (updates progress table)
│           ├── SkipListener.java (writes to DLQ)
│           └── MetricsListener.java (custom Micrometer metrics)
└── batch-report/              <- Report pod
    └── com/bank/rating/report/
        ├── ReportApplication.java
        └── ReportGenerator.java (final report)
```

### 3.2 Worker — Key Components

**Reader:** `JdbcCursorItemReader` with ANSI SQL, `fetchSize` from ConfigMap.

**Processor:** For each `Customer`:
1. Query `transactions` (12-month aggregate)
2. Query `accounts` (count, balance)
3. HTTP call: `/api/v1/external-score/{id}`
4. HTTP call: `/api/v1/average-balance/{id}`
5. Calculate rating using formula
6. Return `CustomerRating`

**Writer:** `JdbcBatchItemWriter` with UPSERT SQL (from property, database-specific).

**Fault tolerance:**
- `retryLimit` from ConfigMap (default: 3) for `WebServiceException`, `ConnectTimeoutException`
- `skipLimit` from ConfigMap (default: 100) for non-retryable errors -> DLQ
- Resilience4j `CircuitBreaker` on web service calls

### 3.3 JVM Parameters — from ConfigMap

```dockerfile
# batch-worker/Dockerfile
FROM eclipse-temurin:21-jre-alpine
COPY target/*.jar /app/app.jar
# JVM_OPTS comes from ConfigMap -> env variable
ENTRYPOINT ["sh", "-c", "java $WORKER_JVM_OPTS -jar /app/app.jar $0 $@"]
```

Different JVM parameters for different pod types:
- **Worker:** ZGC, 1-1.5GB heap, virtual threads
- **Init/Partitioner/Report:** SerialGC, 256-512MB heap, fast start

### 3.4 Graceful Shutdown (Spring Batch 6.0)

Spring Batch 6.0 natively supports graceful shutdown: on SIGTERM the current chunk completes, step transitions to STOPPED, state is saved to JobRepository. Kubernetes `terminationGracePeriodSeconds` from ConfigMap (default: 120s).

### 3.5 Metrics (Push-based via OTLP)

Spring Boot 4.0 -> `management.otlp.metrics.export.url` -> OTel Collector DaemonSet -> Prometheus.

Solves the ephemeral pod problem: pod pushes metrics every N seconds (from ConfigMap), rather than waiting for Prometheus to scrape it.

Custom metrics: `batch.rating.items.processed`, `batch.rating.items.failed`, `batch.rating.webservice.duration`, `batch.rating.partition.progress`.

---

## Part 4: Argo Workflow — Orchestration

### 4.1 Workflow DAG

```
[CronWorkflow: daily at night]
         |
    +----v----+
    |  init   |  Flyway validation, healthchecks, cleanup, startup report
    +----+----+
         |
  +------v------+
  | partitioner |  NTILE query -> JSON [{minId, maxId, partitionId, recordCount}, ...]
  +------+------+
         | withParam (fan-out, parallelism from ConfigMap)
   +-----+-----+-- ... --+
   v     v     v         v
 [w-0] [w-1] ... ...  [w-N]   Spring Batch workers (max N concurrent)
   +-----+-----+-- ... --+
         | depends: all workers
    +----v----+
    | report  |  Final report, statistics, notifications
    +---------+
```

### 4.2 CronWorkflow YAML (parameterized)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: CronWorkflow
metadata:
  name: nightly-rating-calculation
  namespace: batch
spec:
  schedule: "0 2 * * *"
  timezone: "Europe/Berlin"
  concurrencyPolicy: Forbid
  startingDeadlineSeconds: 600
  workflowSpec:
    entrypoint: rating-pipeline
    parallelism: 6
    podGC:
      strategy: OnWorkflowCompletion
    ttlStrategy:
      secondsAfterCompletion: 172800
    serviceAccountName: batch-workflow-sa

    templates:
      - name: rating-pipeline
        dag:
          tasks:
            - name: init
              template: init-step
            - name: partitioner
              template: partitioner-step
              depends: "init"
            - name: workers
              template: worker-step
              depends: "partitioner"
              arguments:
                parameters:
                  - name: partition-data
                    value: "{{item}}"
              withParam: "{{tasks.partitioner.outputs.result}}"
            - name: report
              template: report-step
              depends: "workers"

      - name: init-step
        container:
          image: batch-init:latest
          env:
            - name: JAVA_OPTS
              valueFrom:
                configMapKeyRef:
                  name: batch-tuning
                  key: LIGHTWEIGHT_JVM_OPTS
          envFrom:
            - secretRef:
                name: db-credentials
            - configMapRef:
                name: batch-tuning
          resources:
            requests: { cpu: "500m", memory: "512Mi" }
            limits: { cpu: "1", memory: "1Gi" }

      - name: partitioner-step
        script:
          image: batch-partitioner:latest
          command: ["sh", "-c"]
          args:
            - "java $JAVA_OPTS -jar /app/app.jar"
          env:
            - name: JAVA_OPTS
              valueFrom:
                configMapKeyRef:
                  name: batch-tuning
                  key: LIGHTWEIGHT_JVM_OPTS
          envFrom:
            - secretRef:
                name: db-credentials
            - configMapRef:
                name: batch-tuning
          resources:
            requests: { cpu: "500m", memory: "512Mi" }

      - name: worker-step
        inputs:
          parameters:
            - name: partition-data
        retryStrategy:
          limit: 2
          retryPolicy: OnFailure
          backoff:
            duration: "30s"
            factor: 2
        container:
          image: batch-worker:latest
          args:
            - "--partition.minId={{inputs.parameters.partition-data.minId}}"
            - "--partition.maxId={{inputs.parameters.partition-data.maxId}}"
            - "--partition.id={{inputs.parameters.partition-data.partitionId}}"
          env:
            - name: JAVA_OPTS
              valueFrom:
                configMapKeyRef:
                  name: batch-tuning
                  key: WORKER_JVM_OPTS
            - name: PARTITION_ID
              value: "{{inputs.parameters.partition-data.partitionId}}"
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: "http://otel-collector.monitoring:4318"
          envFrom:
            - secretRef:
                name: db-credentials
            - configMapRef:
                name: batch-tuning
          resources:
            requests: { cpu: "2", memory: "2Gi" }
            limits: { cpu: "2", memory: "2Gi" }

      - name: report-step
        container:
          image: batch-report:latest
          env:
            - name: JAVA_OPTS
              valueFrom:
                configMapKeyRef:
                  name: batch-tuning
                  key: LIGHTWEIGHT_JVM_OPTS
          envFrom:
            - secretRef:
                name: db-credentials
          resources:
            requests: { cpu: "500m", memory: "512Mi" }
```

### 4.3 How Parallelism Works

1. Partitioner generates JSON with N elements (N = PARTITION_COUNT)
2. `withParam` creates N worker tasks
3. Argo starts the first `MAX_PARALLEL_PODS` tasks
4. When a worker completes -> next one from queue starts immediately
5. At any moment at most `MAX_PARALLEL_PODS` pods are running
6. After all workers -> podGC removes pods -> scale to zero

### 4.4 Pod Crash Retry

If a worker pod crashes (OOMKill, node failure):
1. Argo creates a new pod with the same parameters
2. Spring Batch connects to JobRepository, sees unfinished StepExecution
3. Continues from the last committed chunk (not from the beginning)
4. Already-written ratings are overwritten via UPSERT — idempotent

---

## Part 5: Monitoring and Visualization

### 5.1 Three Levels

**Level 1: Argo Workflows UI**
- Real-time DAG: init -> partitioner -> workers -> report
- Each worker — a node in DAG (Running=blue, Succeeded=green, Failed=red)
- Click -> logs, parameters, execution time
- Timeline: when each worker started and finished
- Suitable for business demos

**Level 2: Grafana — Technical Dashboard**
- Job duration vs SLA
- Items/sec (total and per pod)
- Per-partition duration (bar chart — bottlenecks visible)
- Chunk latency (p50, p95, p99)
- Web service latency
- Skip/retry counts
- JVM heap, GC
- Source: Spring Boot OTLP -> OTel Collector -> Prometheus

**Level 3: Grafana — Business Report**
- Gauge 0-100%: processing progress
- ETA: estimated completion time
- Partition heatmap by status
- Totals: processed / errors / skipped
- Source: `rating_processing_progress` table via PostgreSQL datasource

### 5.2 Logging

- Grafana Alloy (DaemonSet): collects logs from `/var/log/pods` even after pod deletion -> Loki
- Structured logging with MDC: `partition.id`, `job.execution.id`, `step.name`

---

## Part 6: React Dashboard

**Stack:** Vite + React + JavaScript + Tailwind CSS  
**Location:** `frontend/` at project root  
**Purpose:** Read-only data viewer for all three MySQL databases + ratings from PostgreSQL

### 6.1 Pages

1. **Customers list** — paginated table (name, email, status, # accounts). Search/filter by name.
2. **Customer detail** (drill-down) — customer info + list of their accounts.
3. **Account detail** (drill-down) — account info + rating (if calculated) + transactions table with pagination.

### 6.2 Technical Decisions

- React Router for navigation between pages
- Tailwind CSS for styling (no component library)
- Native `fetch` API (no axios or react-query — keeping it minimal)
- Server-side pagination (data can be tens of millions of records)
- All API calls go through Spring Cloud Gateway (single origin, no CORS issues)
- Rating data comes from `rating-service` via Gateway (`/api/v1/ratings/{customerId}`)

---

## Part 7: Rating Service (read-only)

**Location:** `services/rating-service/`  
**Port:** 8084  
**Database:** PostgreSQL (`appdb`) — read-only access to `customer_ratings` table  
**Stack:** Spring Boot 4.0, Spring Data JPA, Flyway (validate-only, batch owns migrations)

### 7.1 Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/ratings/{customerId}` | Get rating for a customer (404 if not calculated) |
| `GET` | `/api/v1/ratings` | Paginated list of all ratings |

### 7.2 Why a Separate Service

- **Clean DB ownership:** each service owns one database. No dual-datasource complexity in account-service.
- **Distributed tracing:** adds one more hop in the call chain, more spans for learning.
- **Minimal scope:** one entity, one repository, one controller. Can be built in 30 minutes using existing service patterns.
- **Independence:** batch writes ratings, rating-service reads them. No coupling.

---

## Part 8: Spring Cloud Gateway

**Location:** `services/gateway/`  
**Port:** 8080  
**Decision:** Only API Gateway. No Eureka (k8s provides DNS-based service discovery). No Config Server (k8s provides ConfigMaps).

### 8.1 Why Gateway Is Useful Even in Kubernetes

- Single entry point for the React frontend (one origin, no CORS configuration per service)
- Path-based routing to all backend services
- Ready for Keycloak auth integration later (user's learning goal)
- Centralized rate limiting, request logging

### 8.2 Routes

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: customer-service
          uri: http://customer-service:8081
          predicates:
            - Path=/api/v1/customers/**
        - id: account-service
          uri: http://account-service:8082
          predicates:
            - Path=/api/v1/accounts/**
        - id: transaction-service
          uri: http://transaction-service:8083
          predicates:
            - Path=/api/v1/transactions/**
        - id: rating-service
          uri: http://rating-service:8084
          predicates:
            - Path=/api/v1/ratings/**
```

For local dev: routes point to `localhost:808x`. In k8s: service DNS names.

---

## Checklist: Things to Remember

- **Security:** DB credentials in Kubernetes Secrets, RBAC, NetworkPolicy
- **Overlap prevention:** CronWorkflow `concurrencyPolicy: Forbid`
- **Connection pooling:** HikariCP parameters from ConfigMap. For production — PgBouncer
- **Testing:** Unit (`@SpringBatchTest`), Integration (Testcontainers), Performance (1% data)
- **Data generation:** Script with COUNT parameter, old data cleanup, progress in %

---

## Development Priority

| # | Task | Status | Why this order |
|---|------|--------|---------------|
| 1 | Infrastructure scripts | Done | Foundation for everything |
| 2 | Microservices (customer, account, transaction) | MVP done | Primary learning vehicle |
| 3 | Test Data Generator | Planned | Needed before frontend can show anything |
| 4 | Rating Service (read-only) | Planned | Tiny service, needed by frontend |
| 5 | Spring Cloud Gateway | Planned | Frontend needs single entry point |
| 6 | React Dashboard | Planned | Depends on gateway + data |
| 7 | Monitoring/Tracing instrumentation | Planned | After services are stable |
| 8 | Batch system implementation | Planned | Parallel workstream |
| 9 | GitHub Actions CI | Planned | After code is stable |
| 10 | Helm charts + Argo CD GitOps | Planned | After CI |
