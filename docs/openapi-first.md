# OpenAPI-First Development

## What This Means

OpenAPI-first means the YAML spec is written before any Java code. The spec is the contract. A Maven plugin reads the spec at build time and generates a Spring MVC interface. The developer implements that interface in a `@RestController`. If the spec changes and the controller no longer satisfies the interface, the build fails — the spec and the implementation are always in sync.

```
docs/openapi/<service>.yaml
       │
       │ mvn generate-sources
       ▼
target/generated-sources/openapi/
  com/bank/<service>/api/<Tag>Api.java      ← generated interface with @RequestMapping
  com/bank/<service>/model/CustomerRequest.java  ← generated model class
       │
       │ implements
       ▼
src/main/java/.../CustomerController.java   ← hand-written, contains business logic
```

The generated interface is never edited by hand. Only the `@RestController` class is written and maintained by the developer.

---

## Maven Plugin Configuration

Add to each service's `pom.xml` inside `<build><plugins>`:

```xml
<plugin>
    <groupId>org.openapitools</groupId>
    <artifactId>openapi-generator-maven-plugin</artifactId>
    <version>7.21.0</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
            <configuration>
                <!--
                  Spec lives in docs/openapi/ relative to the repository root.
                  From services/<name>/ the path is ../../docs/openapi/<name>.yaml
                -->
                <inputSpec>${project.basedir}/../../docs/openapi/customer-service.yaml</inputSpec>
                <generatorName>spring</generatorName>
                <output>${project.build.directory}/generated-sources/openapi</output>
                <apiPackage>com.bank.customer.api</apiPackage>
                <modelPackage>com.bank.customer.model</modelPackage>
                <configOptions>
                    <!-- Generate only the interface — controller is hand-written -->
                    <interfaceOnly>true</interfaceOnly>
                    <!--
                      Required for Spring Boot 3+/4+: switches javax.* → jakarta.*
                      Both flags must be set together.
                    -->
                    <useSpringBoot3>true</useSpringBoot3>
                    <useJakartaEe>true</useJakartaEe>
                    <!--
                      Generate @NotNull, @Size, @Min etc. on model fields
                      from OpenAPI 'required' arrays and constraint keywords.
                      Works with Spring's @Valid on controller method parameters.
                    -->
                    <useBeanValidation>true</useBeanValidation>
                    <!-- java.time types: LocalDate, OffsetDateTime — no legacy java.util.Date -->
                    <dateLibrary>java8</dateLibrary>
                    <!--
                      Disable JsonNullable<T> wrapper — adds complexity with no benefit here.
                    -->
                    <openApiNullable>false</openApiNullable>
                    <!--
                      Do not emit default method bodies (501 Not Implemented stubs).
                      The implementing controller must provide all methods — enforced by compiler.
                    -->
                    <skipDefaultInterface>true</skipDefaultInterface>
                </configOptions>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Adjust `<inputSpec>`, `<apiPackage>`, and `<modelPackage>` for each service.

| Service | inputSpec (relative to service root) | apiPackage | modelPackage |
|---|---|---|---|
| customer-service | `../../docs/openapi/customer-service.yaml` | `com.bank.customer.api` | `com.bank.customer.model` |
| account-service | `../../docs/openapi/account-service.yaml` | `com.bank.account.api` | `com.bank.account.model` |
| transaction-service | `../../docs/openapi/transaction-service.yaml` | `com.bank.transaction.api` | `com.bank.transaction.model` |
| rating-service | `../../docs/openapi/rating-service.yaml` | `com.bank.rating.api` | `com.bank.rating.model` |

---

## Required Dependency

The generated code references one runtime library. Add to `<dependencies>`:

```xml
<dependency>
    <groupId>org.openapitools</groupId>
    <artifactId>jackson-databind-nullable</artifactId>
    <version>0.2.6</version>
</dependency>
```

This is needed even with `openApiNullable=false` because some generator templates import it unconditionally.

---

## How to Implement the Generated Interface

Given a spec endpoint:

```yaml
paths:
  /api/v1/customers/{id}:
    get:
      operationId: getCustomerById
      tags: [Customers]
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
            format: int64
      responses:
        "200":
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/CustomerResponse'
        "404":
          description: Customer not found
```

The generator produces:

```java
// target/generated-sources/openapi/com/bank/customer/api/CustomersApi.java
@Tag(name = "Customers")
@RequestMapping("/api/v1")
public interface CustomersApi {

    @GetMapping("/customers/{id}")
    ResponseEntity<CustomerResponse> getCustomerById(@PathVariable("id") Long id);
}
```

The developer writes:

```java
// src/main/java/com/bank/customer/controller/CustomerController.java
@RestController
@RequiredArgsConstructor
public class CustomerController implements CustomersApi {

    private final CustomerService customerService;

    @Override
    public ResponseEntity<CustomerResponse> getCustomerById(Long id) {
        return customerService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
```

The `@RestController` annotation is the only Spring annotation needed on the controller class — all routing annotations (`@GetMapping`, `@RequestMapping`, etc.) come from the generated interface.

---

## Development Workflow

```
1. Edit the YAML spec in docs/openapi/<service>.yaml

2. Run:
     mvn generate-sources -pl services/<service-name>
   Or build the whole thing:
     mvn compile

3. If a new endpoint was added → add the method to the @RestController
   (compiler error if missing)

4. If an endpoint signature changed → update the controller method
   (compiler error if signature doesn't match)

5. Commit the YAML and the controller change in the same commit
   (monorepo: if account-service spec changed a response that transaction-service's
    Feign client depends on, update both sides in the same commit)
```

---

## BigDecimal Mapping for Monetary Fields

OpenAPI `format: double` maps to Java `Double` by default. For monetary fields this causes floating-point imprecision. Override this in each service's Spring configuration:

```java
@Configuration
public class JacksonConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalDeserializer() {
        return builder -> builder
            .featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .featuresToEnable(MapperFeature.DEFAULT_VIEW_INCLUSION);
    }
}
```

With this config, all JSON `number` fields deserialize to `BigDecimal` in Spring's request body parsing, regardless of what the generated model declares. The DB column `DECIMAL(19,4)` then maps cleanly through JPA without precision loss.

---

## Spec Location Convention

OpenAPI specs live in `docs/openapi/` and are the single source of truth. They are **not** copied into `src/main/resources/` — the Maven plugin reads them directly from `docs/openapi/` via the relative path `../../docs/openapi/<name>.yaml`.

This means:
- One spec file, one location — no risk of docs/spec diverging
- The spec is always part of the repository and visible alongside other documentation
- Swagger UI (if added later) can serve the same file via `classpath:` after a build step copies it, or by pointing Spring Docs directly at the `docs/openapi/` path
