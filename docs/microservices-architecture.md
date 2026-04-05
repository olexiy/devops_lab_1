# Microservices Architecture

## Versions

| Component | Version | Notes |
|---|---|---|
| Java | **25** | LTS (released September 2025); virtual threads (Project Loom) |
| Spring Boot | **4.0.5** | Current stable, March 2026 |
| Spring Cloud | **2025.1.2** | Compatible with Boot 4.0.x |
| Spring Cloud Gateway | **5.0.1** | Ships with Spring Cloud 2025.1 |
| Spring Cloud Config | **5.x** | Ships with Spring Cloud 2025.1 |
| Spring Cloud OpenFeign | **5.x** | Ships with Spring Cloud 2025.1 |
| Resilience4j | **2.3.x** | Ships with Spring Cloud 2025.1 |

---

## Services Overview

### Business Services

```
customer-service      port 8081   standalone CRUD, no upstream dependencies
     ↑
account-service       port 8082   accounts per customer; calls customer-service
     ↑
transaction-service   port 8083   transactions per account; calls account-service
```

`account-service` calls `customer-service` to validate that a customer exists before creating an account.
`transaction-service` calls `account-service` to validate an account and update its balance.

These services also expose the endpoints consumed by `batch-worker`:
- `account-service`     → `GET /api/v1/average-balance/{customerId}`
- `transaction-service` → `GET /api/v1/external-score/{customerId}`

### Infrastructure Services

```
config-server    port 8888   centralized configuration for all services
api-gateway      port 8080   single entry point, routing, rate limiting  ← Phase 2
```

---

## Why No Service Registry (No Eureka)

On Kubernetes, services discover each other via K8s DNS automatically:

```
http://customer-service.services.svc.cluster.local:8081
```

Eureka (or Consul) adds an extra stateful component to operate, monitor, and keep highly available — with no benefit when K8s handles it natively. The Config Server URL is injected into each pod via a K8s ConfigMap environment variable.

---

## Database per Service

Each service owns its own schema. No service reads another service's database directly — all cross-service data access goes through the REST API.

| Service | Engine | Schema |
|---|---|---|
| customer-service | MySQL 8.3 | `customers_db` |
| account-service | MySQL 8.3 | `accounts_db` |
| transaction-service | MySQL 8.3 | `transactions_db` |
| batch-worker (ratings) | PostgreSQL 16 | `ratings_db` |

Flyway migrations live inside each service at `src/main/resources/db/migration/`, and Spring Boot applies them automatically on startup.

---

## Three Phases

### Phase 1 — MVP

Goal: three services running, talking to each other, data persists.

**1. Config Server** (`services/config-server`)

Serves configuration to all services over HTTP. In Phase 1 it reads config files from its own classpath (`src/main/resources/config/`). In Phase 3 it switches to Git-backed config from this repository.

Each business service bootstraps with:
```yaml
spring:
  config:
    import: "configserver:http://config-server:8888"
```

**2. Three business services** (generate in Spring Initializr — see section below)

Each service:
- Manages its own MySQL schema via Flyway
- Exposes REST endpoints
- Calls upstream services via OpenFeign
- Wraps remote calls in Resilience4j circuit breakers
- Publishes metrics to Prometheus and traces to OTel Collector

**3. Resilience4j circuit breakers**

`account-service` and `transaction-service` each have one `@CircuitBreaker` + `@Retry` wrapping the OpenFeign client that calls the upstream service. The fallback returns a safe default value (not an exception) so the caller can still complete its own response.

**4. Micrometer instrumentation (basic)**

- Prometheus endpoint (`/actuator/prometheus`) scraped by Prometheus via ServiceMonitor
- OTLP exporter sends traces to `otel-collector:4318` → Tempo
- Trace IDs appear in logs automatically (MDC injection via Micrometer Tracing)

---

### Phase 2 — Observability + API Gateway

Goal: full distributed traces spanning all three services; single external entry point.

**1. API Gateway** (`services/api-gateway`)

Reactive Spring Cloud Gateway. Routes external requests to the correct service:

```
GET /api/customers/**   → customer-service:8081
GET /api/accounts/**    → account-service:8082
GET /api/transactions/**→ transaction-service:8083
```

Additional responsibilities:
- Per-client rate limiting (`RequestRateLimiter` filter)
- Circuit breaker at gateway level (protects gateway from downstream failures)
- Central request/response logging

**2. Distributed tracing**

All services export traces via OTLP to the OTel Collector already running in the cluster (`otel-collector:4318`). OTel Collector fans out to Tempo. Trace context propagates automatically across OpenFeign calls via W3C Trace Context headers.

In Grafana: Loki → Tempo link (click a `traceId` in a log line → open the trace).

**3. Service dashboards in Grafana**

One dashboard per service showing:
- RPS (requests per second)
- P99 / P50 latency
- Error rate (5xx)
- Active DB connections (HikariCP pool)
- Circuit breaker state (CLOSED / OPEN / HALF_OPEN)

---

### Phase 3 — GitOps + CI/CD

Goal: every merge to `main` automatically deploys to the cluster.

**1. Helm charts** — `services/<name>/helm/` for each service + gateway + config-server

**2. Argo CD Applications** — `argocd/` folder, one `Application` manifest per service.
Argo CD watches the Git repo and syncs whenever a chart changes.

**3. GitHub Actions** — on every push to `main`:
- Build + test
- Build Docker image, push to registry
- Update the image tag in the Helm chart
- Argo CD detects the change and rolls out

**4. Keycloak (optional)**

JWT authentication at the Gateway level. Business services trust the Gateway and do not validate tokens themselves. Keycloak can be added in Phase 3 without changing the business services.

---

## Monorepo and CI/CD

All services live in a single Git repository. This does **not** cause problems for CI/CD — the standard solution is path-based isolation.

### GitHub Actions — one workflow per service

Each service has its own workflow file that triggers only when its own directory changes:

```yaml
# .github/workflows/customer-service.yml
on:
  push:
    paths:
      - 'services/customer-service/**'
```

A push that changes only `account-service` will trigger only the `account-service` pipeline. Other services are not built or deployed.

### Argo CD — one Application per service path

Each Argo CD `Application` points to a specific path in the repository:

```yaml
source:
  repoURL: https://github.com/olexiy/devops_lab_1
  path: services/customer-service/helm
  targetRevision: main
```

Argo CD tracks only that path. When the Helm chart at that path changes (e.g., a new image tag), only that service is synced and redeployed.

### Benefits of monorepo for this project

- All infrastructure, scripts, configs, and services in one place — one `git clone` to get everything
- Atomic commits when changing an API contract between two services (both sides in one commit)
- Shared `docs/`, `monitoring/`, `config/` without cross-repo references
- No operational overhead of managing multiple repositories, tokens, and CI configs

The only real drawback of a monorepo is at very large scale (thousands of services, gigabytes of history — the Google/Meta problem). At this project's scale it is not relevant.

---

## Spring Initializr — What to Select

Go to [start.spring.io](https://start.spring.io), set **Spring Boot 4.0.5**, **Java 25**, packaging **Jar**.

### customer-service

| Dependency | Initializr name | Starter |
|---|---|---|
| Spring Web | Spring Web | `spring-boot-starter-web` |
| Spring Data JPA | Spring Data JPA | `spring-boot-starter-data-jpa` |
| Flyway Migration | Flyway Migration | `flyway-core` |
| Spring Boot Actuator | Spring Boot Actuator | `spring-boot-starter-actuator` |
| Config Client | Config Client | `spring-cloud-starter-config` |
| OpenFeign | OpenFeign | `spring-cloud-starter-openfeign` |
| Resilience4j | Resilience4j | `spring-cloud-starter-circuitbreaker-resilience4j` |
| Validation | Validation | `spring-boot-starter-validation` |
| MySQL Driver | MySQL Driver | `mysql-connector-j` |
| Prometheus metrics | **Prometheus** | `micrometer-registry-prometheus` |
| OTel tracing + OTLP export | **OpenTelemetry** | `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` |

> OpenFeign is listed here for consistency but customer-service has no upstream calls in Phase 1.
> Add it anyway — you will need it once you add health-check calls or integrate with batch endpoints.

### account-service

Same as `customer-service`. OpenFeign is needed from day 1 (calls customer-service).

### transaction-service

Same as `customer-service`. OpenFeign is needed from day 1 (calls account-service).

### config-server

| Dependency | Starter |
|---|---|
| Config Server | `spring-cloud-config-server` |
| Spring Boot Actuator | `spring-boot-starter-actuator` |

### api-gateway (Phase 2)

| Dependency | Starter |
|---|---|
| Gateway | `spring-cloud-starter-gateway` — **reactive, NOT MVC** |
| Spring Boot Actuator | `spring-boot-starter-actuator` |
| Config Client | `spring-cloud-starter-config` |
| Resilience4j | `spring-cloud-starter-circuitbreaker-reactor-resilience4j` |
| **Prometheus** | `micrometer-registry-prometheus` |
| **OpenTelemetry** | `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` |

> Gateway is reactive (WebFlux-based). Do **not** add `Spring Web` (MVC) — they conflict.

---

## Key Configuration Patterns

### application.yml for each business service

```yaml
spring:
  application:
    name: customer-service          # must match the config file name in config-server
  config:
    import: "configserver:http://config-server:8888"
  datasource:
    url: jdbc:mysql://source-db.databases.svc.cluster.local:3306/customers_db
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate            # Flyway manages the schema; Hibernate only validates
  flyway:
    url: ${spring.datasource.url}
    user: ${DB_USER}
    password: ${DB_PASSWORD}

server:
  port: 8081

management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus
  tracing:
    sampling:
      probability: 1.0              # 100% in dev; drop to 0.1 in prod
  otlp:
    metrics:
      export:
        url: http://otel-collector.monitoring.svc.cluster.local:4318/v1/metrics
    tracing:
      endpoint: http://otel-collector.monitoring.svc.cluster.local:4318/v1/traces
```

### Resilience4j in account-service (calling customer-service)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      customer-service:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        registerHealthIndicator: true
  retry:
    instances:
      customer-service:
        maxAttempts: 3
        waitDuration: 500ms
  timelimiter:
    instances:
      customer-service:
        timeoutDuration: 3s
```

### Config Server — Git-backed (Phase 3)

```yaml
server:
  port: 8888

spring:
  application:
    name: config-server
  cloud:
    config:
      server:
        git:
          uri: https://github.com/olexiy/devops_lab_1
          search-paths: config/services
          default-label: main
```

### API Gateway routing (Phase 2)

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: customer-service
          uri: http://customer-service:8081
          predicates:
            - Path=/api/customers/**
        - id: account-service
          uri: http://account-service:8082
          predicates:
            - Path=/api/accounts/**
        - id: transaction-service
          uri: http://transaction-service:8083
          predicates:
            - Path=/api/transactions/**
```

---

## Project Structure (after Phase 1)

```
services/
  config-server/           Spring Boot — Config Server
  customer-service/        Spring Boot — CRUD, MySQL customers_db
  account-service/         Spring Boot — accounts, MySQL accounts_db, calls customer-service
  transaction-service/     Spring Boot — transactions, MySQL transactions_db, calls account-service
```

Each service folder is a self-contained Maven project with its own `pom.xml`.
No parent POM across services — they are independent deployable units.

---

## Inter-Service Call Chain (example flow)

```
POST /api/transactions
  transaction-service
    → OpenFeign: GET account-service/api/accounts/{id}     (validate + get balance)
        → OpenFeign: GET customer-service/api/customers/{id}  (validate customer exists)
        ← 200 OK  { id, customerId, balance }
    ← 200 OK  { id, accountId, ... }
  → write transaction to transactions_db
  → OpenFeign: PATCH account-service/api/accounts/{id}/balance  (update balance)
  ← 201 Created
```

Every OpenFeign call has a circuit breaker. If `customer-service` is down,
`account-service` opens the circuit and returns a fallback — `transaction-service`
does not cascade-fail, it receives the fallback and can decide whether to proceed or reject.

---

## OpenAPI-First

All three services follow an OpenAPI-first approach: the YAML spec is written first, and a Maven plugin generates the Spring controller interface from it. The developer implements the interface — the compiler enforces that spec and implementation stay in sync.

- **Specs:** `docs/openapi/customer-service.yaml`, `account-service.yaml`, `transaction-service.yaml`
- **Per-service docs** (role + DB schema): `docs/services/`
- **Full workflow + Maven plugin config:** [docs/openapi-first.md](openapi-first.md)
