# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Two parallel workstreams sharing the same Kubernetes cluster and observability stack:

1. **Batch system** — nightly recalculation of customer reliability ratings (30-50 million records, ~2-hour SLA). Spring Batch + Argo Workflows.
2. **Microservices** — Spring Boot REST services for customer account management. Primary learning vehicle for monitoring, distributed tracing, Spring Cloud, and GitOps.

**Authoritative design reference:** [architecture-plan.md](architecture-plan.md)

**Current state:**
- `customer-service` — MVP complete, 16 integration tests green
- `account-service` — MVP complete, 13 integration tests green
- `transaction-service` — MVP complete, 15 integration tests written (not yet run)
- `rating-service` — MVP complete, 6 integration tests green (`services/rating-service/`)
- `gateway` — MVP complete, 5 tests green (`services/infra/gateway/`)
- `frontend` — planned (Vite + React + Tailwind, read-only dashboard)
- `batch-app` — directory structure exists, no code yet
- `data-generator` — complete (`data-generator/` at repo root)

## Critical Rule: Persist All Plans

**Any planning decisions, architecture changes, or feature designs discussed in chat MUST be saved to `architecture-plan.md` or a dedicated document before the session ends.** Do not rely on chat history — it is lost on `/clear` or context compression.

## Version Policy

**Always web-search the current stable version** of any framework or library before writing or reviewing code. Never rely on memory for version numbers. Use only the latest stable release — no deprecated APIs.

## Development Roadmap

| # | Task | Status |
|---|------|--------|
| 1 | Infrastructure scripts + observability stack | Done |
| 2 | Microservices (customer, account, transaction) | MVP done |
| 3 | Test Data Generator (Python) | Done |
| 4 | Rating Service (read-only, PostgreSQL) | Done |
| 5 | Spring Cloud Gateway | Done |
| 6 | React Dashboard | Planned |
| 7 | Monitoring/Tracing instrumentation | Planned |
| 8 | Batch system implementation | Planned |
| 9 | GitHub Actions CI | Planned |
| 10 | Helm charts + Argo CD GitOps | Planned |

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
```

For microservices-only local dev (no k8s needed):
```powershell
cd services && docker compose up
```

## Service Ports

| Service | Port | Database |
|---------|------|----------|
| customer-service | 8081 | MySQL `customers_db` |
| account-service | 8082 | MySQL `accounts_db` |
| transaction-service | 8083 | MySQL `transactions_db` |
| rating-service | 8084 | PostgreSQL `appdb` |
| gateway | 8080 | none |

Inter-service calls: `transaction-service -> account-service -> customer-service`

## Spring Boot 4 + Spring Cloud 2025 — Known Breaking Changes

This project uses Spring Boot 4.0.x. Apply these rules immediately.

### Jackson 3.x (group ID changed)
- Old: `com.fasterxml.jackson.databind.DeserializationFeature`
- New: `tools.jackson.databind.DeserializationFeature`
- Old: `org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer`
- New: `org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer` (accepts `tools.jackson` builder)

### Test infrastructure removed
- `@AutoConfigureMockMvc` — **removed**. Use `MockMvcBuilders.webAppContextSetup(wac).build()` in `@BeforeEach`.
- `TestRestTemplate` — **removed**. Use MockMvc exclusively.
- Canonical test pattern:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class MyControllerIT {
    @Autowired WebApplicationContext wac;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }
}
```

### Maven Surefire — IT tests excluded by default
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <includes>
            <include>**/*Tests.java</include>
            <include>**/*Test.java</include>
            <include>**/*IT.java</include>
        </includes>
    </configuration>
</plugin>
```

### WireMock
- **No wildcard static import:** `import static com.github.tomakehurst.wiremock.client.WireMock.*` conflicts with MockMvc. Always use explicit `WireMock.get(...)`, `WireMock.urlPathEqualTo(...)`.
- **Fixed port (not dynamic):** Use fixed port matching `application.yaml`. `@DynamicPropertySource` arrives too late with `@Nested` classes.

```java
@RegisterExtension
static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().port(9876))
        .build();
```

### Spring Cloud Gateway 5.x — property prefix changed
Old: `spring.cloud.gateway.*`  
New: `spring.cloud.gateway.server.webflux.*` (for `spring-cloud-starter-gateway-server-webflux`)

All route definitions and CORS config must use the new prefix. Old prefix is silently ignored — routes will be empty at runtime.

### Java version
All services use `<java.version>21</java.version>`. Java 25 is not supported.

### Other rules
- Unnamed variable `_` is preview in Java 21 — use named variable instead.
- Datasource URL — always include `?serverTimezone=UTC`.
- OpenFeign `FeignException` not auto-mapped — add handler in `GlobalExceptionHandler`.

## Platform Notes

All scripts in `scripts/` are Windows PowerShell (`.ps1`). Mac equivalents (`scripts/mac/*.sh`) are a TODO item.
