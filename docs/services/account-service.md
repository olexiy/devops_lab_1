# account-service

**Port:** 8082  
**Database:** MySQL 8.3 — `accounts_db`  
**OpenAPI spec:** [`docs/openapi/account-service.yaml`](../openapi/account-service.yaml)  
**Upstream dependencies:** customer-service (via OpenFeign)

---

## Role

Manages bank accounts. Each account belongs to exactly one customer. Before opening an account, account-service validates that the customer exists by calling customer-service. 

This service also exposes the batch endpoint `GET /api/v1/average-balance/{customerId}`, which the nightly batch-worker calls to get a customer's average balance across all active accounts.

---

## Feign Client (→ customer-service)

Called when:
- `POST /api/v1/accounts` — validate customer exists before creating account
- `GET /api/v1/accounts/{id}` called by transaction-service — account validation triggers a customer lookup to provide full context (optional, depending on implementation)

Circuit breaker configuration (in `application.yml`):

```yaml
resilience4j:
  circuitbreaker:
    instances:
      customer-service:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
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

Fallback strategy: if customer-service is unreachable when validating during account creation, return `503 Service Unavailable` — do not create the account with an unverified customer.

---

## Database Schema

Flyway migration: `src/main/resources/db/migration/V1__create_accounts.sql`

```sql
CREATE TABLE accounts (
    id             BIGINT          NOT NULL AUTO_INCREMENT,
    account_number VARCHAR(20)     NOT NULL,
    customer_id    BIGINT          NOT NULL,
    account_type   ENUM('CHECKING','SAVINGS','CREDIT','DEPOSIT')
                                   NOT NULL,
    status         ENUM('ACTIVE','FROZEN','CLOSED')
                                   NOT NULL DEFAULT 'ACTIVE',
    currency       CHAR(3)         NOT NULL DEFAULT 'EUR',
    balance        DECIMAL(19,4)   NOT NULL DEFAULT 0.0000,
    credit_limit   DECIMAL(19,4)   DEFAULT NULL,
    open_date      DATE            NOT NULL,
    close_date     DATE            DEFAULT NULL,
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                            ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_accounts_number          (account_number),
    INDEX         idx_accounts_customer_id (customer_id),
    INDEX         idx_accounts_customer_type (customer_id, account_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### Key design decisions

- `customer_id` has no physical FK constraint — the customer lives in a separate database. Referential integrity is enforced at the application layer via the Feign validation call.
- `DECIMAL(19,4)` for all monetary columns — no floating-point imprecision.
- `credit_limit` is `NULL` for non-CREDIT account types.
- `account_number` is server-generated (business key). Clients never supply it.
- Composite index `(customer_id, account_type)` supports the `GET /api/v1/average-balance/{customerId}` aggregation query directly.
- Status lifecycle: `ACTIVE → FROZEN → CLOSED`. Closed accounts are soft-deleted.

---

## Endpoints

Full request/response shapes are defined in the OpenAPI spec.

| Method | Path | Description | Caller |
|---|---|---|---|
| `POST` | `/api/v1/accounts` | Open account (validates customer via Feign) | Frontend |
| `GET` | `/api/v1/accounts` | List — paginated; filter by `customerId`, `status` | Frontend |
| `GET` | `/api/v1/accounts/{id}` | Get account by ID | Frontend, transaction-service |
| `GET` | `/api/v1/accounts/number/{accountNumber}` | Get by account number | Frontend |
| `GET` | `/api/v1/accounts/customer/{customerId}` | All accounts for a customer | Frontend |
| `PUT` | `/api/v1/accounts/{id}` | Full metadata update | Frontend |
| `PATCH` | `/api/v1/accounts/{id}/status` | Change status (freeze, close) | Frontend |
| `PATCH` | `/api/v1/accounts/{id}/balance` | Update balance — **internal only** | transaction-service |
| `DELETE` | `/api/v1/accounts/{id}` | Soft delete (sets `status = CLOSED`) | Frontend |
| `GET` | `/api/v1/average-balance/{customerId}` | Average balance across active accounts | batch-worker |

### Internal endpoint note

`PATCH /api/v1/accounts/{id}/balance` is called only by transaction-service. It must not be routed through the API Gateway. In Phase 2, the Gateway routing table covers `/api/accounts/**` paths — exclude this balance path from external routing, or add an internal-only header check.
