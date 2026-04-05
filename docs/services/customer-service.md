# customer-service

**Port:** 8081  
**Database:** MySQL 8.3 — `customers_db`  
**OpenAPI spec:** [`docs/openapi/customer-service.yaml`](../openapi/customer-service.yaml)  
**Upstream dependencies:** none

---

## Role

Manages the customer master data. This is the leaf node of the service dependency graph — no other service is called from here. All other services (account-service, transaction-service) validate that a customer exists by calling this service.

---

## Database Schema

Flyway migration: `src/main/resources/db/migration/V1__create_customers.sql`

```sql
CREATE TABLE customers (
    id             BIGINT          NOT NULL AUTO_INCREMENT,
    first_name     VARCHAR(100)    NOT NULL,
    last_name      VARCHAR(100)    NOT NULL,
    email          VARCHAR(255)    NOT NULL,
    phone          VARCHAR(30),
    date_of_birth  DATE            NOT NULL,
    status         ENUM('ACTIVE','INACTIVE','BLOCKED','CLOSED')
                                   NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                            ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_customers_email   (email),
    INDEX         idx_customers_status    (status),
    INDEX         idx_customers_last_name (last_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### Key design decisions

- `id` is a BIGINT surrogate key. The batch-worker's NTILE partitioner splits the `customers` table by numeric `id` — BIGINT is required.
- `email` is the unique business key. The internal `id` is never exposed in URLs consumed by end users.
- `status` lifecycle: `ACTIVE → INACTIVE → BLOCKED → CLOSED`. Closed customers are never physically deleted — soft delete only.
- No address columns in v1. Address is a separate aggregate if needed later.

---

## Endpoints

Full request/response shapes are defined in the OpenAPI spec.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/customers` | Create a new customer |
| `GET` | `/api/v1/customers` | List customers — paginated; filter by `status`, `lastName` |
| `GET` | `/api/v1/customers/{id}` | Get customer by ID |
| `GET` | `/api/v1/customers/email/{email}` | Get customer by email (unique key lookup) |
| `PUT` | `/api/v1/customers/{id}` | Full update of customer data |
| `PATCH` | `/api/v1/customers/{id}/status` | Change status only |
| `DELETE` | `/api/v1/customers/{id}` | Soft delete — sets `status = CLOSED` |

---

## Flyway Note

Spring Boot applies migrations automatically on startup via:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate   # Hibernate validates schema; Flyway owns it
  flyway:
    locations: classpath:db/migration
```

`ddl-auto: validate` means Hibernate will fail on startup if the schema does not match the entity mapping — useful early warning if a migration was skipped.
