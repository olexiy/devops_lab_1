package de.olexiy.devopsplayground.demo.transaction_service;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import de.olexiy.devopsplayground.demo.transaction_service.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * account-service.url in test/resources/application.yaml is http://localhost:9877
 * WireMock listens on the same fixed port 9877.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class TransactionControllerIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.3");

    // Fixed port matching account-service.url in test application.yaml
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(9877))
            .build();

    @Autowired WebApplicationContext wac;
    @Autowired TransactionRepository repository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        repository.deleteAll();
        wireMock.resetAll();
    }

    private void stubAccountActive(long accountId, long customerId, double balance) {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v1/accounts/" + accountId))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": %d,
                                  "customerId": %d,
                                  "status": "ACTIVE",
                                  "balance": %s,
                                  "currency": "EUR",
                                  "creditLimit": null
                                }
                                """.formatted(accountId, customerId, balance))));
    }

    private void stubAccountBalancePatch(long accountId) {
        wireMock.stubFor(WireMock.patch(WireMock.urlPathEqualTo("/api/v1/accounts/" + accountId + "/balance"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));
    }

    private void stubAccountNotFound(long accountId) {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v1/accounts/" + accountId))
                .willReturn(WireMock.aResponse().withStatus(404)));
    }

    private void stubAccountServiceDown(long accountId) {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v1/accounts/" + accountId))
                .willReturn(WireMock.aResponse().withStatus(503)));
    }

    private static final String CREDIT_BODY = """
            {
              "accountId": 7,
              "customerId": 42,
              "transactionType": "CREDIT",
              "amount": 500.00,
              "currency": "EUR",
              "transactionDate": "2026-04-05T14:30:00Z"
            }
            """;

    private static final String DEBIT_BODY = """
            {
              "accountId": 7,
              "customerId": 42,
              "transactionType": "DEBIT",
              "amount": 200.00,
              "currency": "EUR",
              "transactionDate": "2026-04-05T15:00:00Z"
            }
            """;

    @Nested
    class CreateTransaction {

        @Test
        void create_creditTransaction_returns201() throws Exception {
            stubAccountActive(7L, 42L, 1000.0);
            stubAccountBalancePatch(7L);

            mockMvc.perform(post("/api/v1/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREDIT_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.referenceNumber").isString())
                    .andExpect(jsonPath("$.transactionType").value("CREDIT"))
                    .andExpect(jsonPath("$.amount").value(500.0))
                    .andExpect(jsonPath("$.balanceAfter").value(1500.0));
        }

        @Test
        void create_debitWithSufficientFunds_returns201() throws Exception {
            stubAccountActive(7L, 42L, 1000.0);
            stubAccountBalancePatch(7L);

            mockMvc.perform(post("/api/v1/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(DEBIT_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transactionType").value("DEBIT"))
                    .andExpect(jsonPath("$.balanceAfter").value(800.0));
        }

        @Test
        void create_accountNotFound_returns404() throws Exception {
            stubAccountNotFound(7L);

            mockMvc.perform(post("/api/v1/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREDIT_BODY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        void create_accountServiceDown_returns503() throws Exception {
            stubAccountServiceDown(7L);

            mockMvc.perform(post("/api/v1/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREDIT_BODY))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value(503));
        }

        @Test
        void create_debitExceedsBalance_returns409() throws Exception {
            stubAccountActive(7L, 42L, 100.0);

            mockMvc.perform(post("/api/v1/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "accountId": 7,
                                      "customerId": 42,
                                      "transactionType": "DEBIT",
                                      "amount": 500.00,
                                      "currency": "EUR",
                                      "transactionDate": "2026-04-05T15:00:00Z"
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        void create_missingRequired_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "accountId": 7, "transactionType": "CREDIT" }
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class GetTransaction {

        @Test
        void getById_existing_returns200() throws Exception {
            stubAccountActive(7L, 42L, 1000.0);
            stubAccountBalancePatch(7L);
            var location = createTransaction(CREDIT_BODY);

            mockMvc.perform(get(location))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionType").value("CREDIT"))
                    .andExpect(jsonPath("$.currency").value("EUR"));
        }

        @Test
        void getById_notFound_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/transactions/9999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        void getByReference_existing_returns200() throws Exception {
            stubAccountActive(7L, 42L, 1000.0);
            stubAccountBalancePatch(7L);
            createTransaction(CREDIT_BODY);

            var ref = repository.findAll().get(0).getReferenceNumber();

            mockMvc.perform(get("/api/v1/transactions/reference/" + ref))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.referenceNumber").value(ref));
        }

        @Test
        void getByReference_notFound_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/transactions/reference/00000000-0000-0000-0000-000000000000"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class ListTransactions {

        @Test
        void listAll_returns200WithPageMetadata() throws Exception {
            stubAccountActive(7L, 42L, 1000.0);
            stubAccountBalancePatch(7L);
            createTransaction(CREDIT_BODY);
            createTransaction(DEBIT_BODY);

            mockMvc.perform(get("/api/v1/transactions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.page.totalElements").value(2));
        }

        @Test
        void getByAccount_returns200() throws Exception {
            stubAccountActive(7L, 42L, 1000.0);
            stubAccountBalancePatch(7L);
            createTransaction(CREDIT_BODY);

            mockMvc.perform(get("/api/v1/transactions/account/7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].accountId").value(7));
        }

        @Test
        void getByCustomer_returns200() throws Exception {
            stubAccountActive(7L, 42L, 1000.0);
            stubAccountBalancePatch(7L);
            createTransaction(CREDIT_BODY);

            mockMvc.perform(get("/api/v1/transactions/customer/42"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].customerId").value(42));
        }
    }

    @Nested
    class ExternalScore {

        @Test
        void getExternalScore_withTransactions_returns200() throws Exception {
            stubAccountActive(7L, 42L, 1000.0);
            stubAccountBalancePatch(7L);
            createTransaction(CREDIT_BODY);
            createTransaction(DEBIT_BODY);

            mockMvc.perform(get("/api/v1/external-score/42"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customerId").value(42))
                    .andExpect(jsonPath("$.score").isNumber())
                    .andExpect(jsonPath("$.scoreGrade").isString())
                    .andExpect(jsonPath("$.transactionCount").value(2))
                    .andExpect(jsonPath("$.windowMonths").value(12));
        }

        @Test
        void getExternalScore_noTransactions_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/external-score/999"))
                    .andExpect(status().isNotFound());
        }
    }

    private String createTransaction(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
    }
}
