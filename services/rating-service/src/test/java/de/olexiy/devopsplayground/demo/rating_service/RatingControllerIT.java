package de.olexiy.devopsplayground.demo.rating_service;

import de.olexiy.devopsplayground.demo.rating_service.entity.CustomerRating;
import de.olexiy.devopsplayground.demo.rating_service.repository.CustomerRatingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class RatingControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired WebApplicationContext wac;
    @Autowired CustomerRatingRepository repository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        repository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/ratings/{customerId}
    // -----------------------------------------------------------------------
    @Nested
    class GetByCustomerId {

        @Test
        void exists_returns200WithBody() throws Exception {
            insertRating(42L, "85.00", "A", "LOW");

            mockMvc.perform(get("/api/v1/ratings/42"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customerId").value(42))
                    .andExpect(jsonPath("$.ratingScore").value(85.0))
                    .andExpect(jsonPath("$.ratingClass").value("A"))
                    .andExpect(jsonPath("$.riskLevel").value("LOW"))
                    .andExpect(jsonPath("$.calculatedAt").isNotEmpty())
                    .andExpect(jsonPath("$.calculationVersion").value("1.0"));
        }

        @Test
        void notFound_returns404WithErrorBody() throws Exception {
            mockMvc.perform(get("/api/v1/ratings/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value(containsString("999")));
        }
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/ratings
    // -----------------------------------------------------------------------
    @Nested
    class GetAll {

        @Test
        void withData_returnsPaginatedList() throws Exception {
            insertRating(1L, "90.00", "A", "LOW");
            insertRating(2L, "70.00", "B", "MEDIUM");
            insertRating(3L, "50.00", "C", "HIGH");

            mockMvc.perform(get("/api/v1/ratings?page=0&size=2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.page.totalElements").value(3))
                    .andExpect(jsonPath("$.page.totalPages").value(2))
                    .andExpect(jsonPath("$.page.size").value(2));
        }

        @Test
        void empty_returnsEmptyPage() throws Exception {
            mockMvc.perform(get("/api/v1/ratings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0)))
                    .andExpect(jsonPath("$.page.totalElements").value(0));
        }
    }

    // -----------------------------------------------------------------------
    // Actuator
    // -----------------------------------------------------------------------
    @Nested
    class Actuator {

        @Test
        void health_returns200WithStatusUp() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }
    }

    // -----------------------------------------------------------------------

    private void insertRating(Long customerId, String score, String ratingClass, String riskLevel) {
        CustomerRating rating = new CustomerRating();
        rating.setCustomerId(customerId);
        rating.setRatingScore(new BigDecimal(score));
        rating.setRatingClass(ratingClass);
        rating.setRiskLevel(riskLevel);
        rating.setCalculatedAt(LocalDateTime.now());
        rating.setCalculationVersion("1.0");
        rating.setAvgBalance12m(new BigDecimal("15000.00"));
        rating.setProductCount(3);
        rating.setTransactionVolume12m(new BigDecimal("45000.00"));
        repository.save(rating);
    }
}
