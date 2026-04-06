package de.olexiy.devopsplayground.demo.account_service;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import de.olexiy.devopsplayground.demo.account_service.repository.AccountRepository;
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
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * customer-service.url in test/resources/application.yaml is http://localhost:9876
 * WireMock listens on the same fixed port 9876.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class AccountControllerIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.3");

    // Fixed port matching customer-service.url in test application.yaml
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(9876))
            .build();

    @Autowired WebApplicationContext wac;
    @Autowired AccountRepository repository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        repository.deleteAll();
        wireMock.resetAll();
    }

    private void stubCustomerActive(long customerId) {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v1/customers/" + customerId))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id": %d, "status": "ACTIVE"}
                                """.formatted(customerId))));
    }

    private void stubCustomerNotFound(long customerId) {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v1/customers/" + customerId))
                .willReturn(WireMock.aResponse().withStatus(404)));
    }

    private void stubCustomerServiceDown(long customerId) {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v1/customers/" + customerId))
                .willReturn(WireMock.aResponse().withStatus(503)));
    }

    private static final String ACCOUNT_BODY = """
            {
              "customerId": 1,
              "accountType": "CHECKING",
              "currency": "EUR",
              "openDate": "2026-01-15"
            }
            """;

    @Nested
    class CreateAccount {

        @Test
        void create_validRequest_customerActive_returns201() throws Exception {
            stubCustomerActive(1L);

            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ACCOUNT_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.balance").value(0.0))
                    .andExpect(jsonPath("$.accountNumber").value(matchesPattern("ACC-\\d{8}-\\d{6}")));
        }

        @Test
        void create_customerNotFound_returns404() throws Exception {
            stubCustomerNotFound(1L);

            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ACCOUNT_BODY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        void create_customerServiceDown_returns503() throws Exception {
            stubCustomerServiceDown(1L);

            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ACCOUNT_BODY))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value(503));
        }

        @Test
        void create_missingRequired_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "customerId": 1, "accountType": "CHECKING" }
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class GetAccount {

        @Test
        void getById_existing_returns200() throws Exception {
            stubCustomerActive(1L);
            var location = createAccount(ACCOUNT_BODY);

            mockMvc.perform(get(location))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountType").value("CHECKING"))
                    .andExpect(jsonPath("$.currency").value("EUR"));
        }

        @Test
        void getById_notFound_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/9999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    @Nested
    class Balance {

        @Test
        void updateBalance_credit_increasesBalance() throws Exception {
            stubCustomerActive(1L);
            var location = createAccount(ACCOUNT_BODY);
            var id = idFromLocation(location);

            mockMvc.perform(patch("/api/v1/accounts/" + id + "/balance")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "amount": 1000.00, "operation": "CREDIT" }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(1000.0));
        }

        @Test
        void updateBalance_debitExceedsBalance_returns409() throws Exception {
            stubCustomerActive(1L);
            var location = createAccount(ACCOUNT_BODY);
            var id = idFromLocation(location);

            mockMvc.perform(patch("/api/v1/accounts/" + id + "/balance")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "amount": 500.00, "operation": "DEBIT" }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }
    }

    @Nested
    class AverageBalance {

        @Test
        void getAverageBalance_withAccounts_returnsCorrectAverage() throws Exception {
            stubCustomerActive(1L);
            var id1 = idFromLocation(createAccount(ACCOUNT_BODY));
            var id2 = idFromLocation(createAccount(ACCOUNT_BODY));

            mockMvc.perform(patch("/api/v1/accounts/" + id1 + "/balance")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "amount": 1000.00, "operation": "CREDIT" }
                                    """))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/v1/accounts/" + id2 + "/balance")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "amount": 3000.00, "operation": "CREDIT" }
                                    """))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/average-balance/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customerId").value(1))
                    .andExpect(jsonPath("$.averageBalance").value(2000.0))
                    .andExpect(jsonPath("$.accountCount").value(2));
        }

        @Test
        void getAverageBalance_noAccounts_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/average-balance/999"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class StatusAndDelete {

        @Test
        void updateStatus_freeze_returns200() throws Exception {
            stubCustomerActive(1L);
            var location = createAccount(ACCOUNT_BODY);
            var id = idFromLocation(location);

            mockMvc.perform(patch("/api/v1/accounts/" + id + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "status": "FROZEN" }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FROZEN"));
        }

        @Test
        void delete_existing_returns204AndStatusClosed() throws Exception {
            stubCustomerActive(1L);
            var location = createAccount(ACCOUNT_BODY);
            var id = idFromLocation(location);

            mockMvc.perform(delete("/api/v1/accounts/" + id))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/accounts/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"))
                    .andExpect(jsonPath("$.closeDate").isNotEmpty());
        }
    }

    private String createAccount(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
    }

    private long idFromLocation(String location) {
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }
}
