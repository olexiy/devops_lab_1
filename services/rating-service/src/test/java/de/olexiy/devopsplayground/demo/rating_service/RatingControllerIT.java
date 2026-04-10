package de.olexiy.devopsplayground.demo.rating_service;

import de.olexiy.devopsplayground.demo.rating_service.repository.CustomerRatingRepository;
import de.olexiy.devopsplayground.demo.rating_service.entity.CustomerRating;
import org.junit.jupiter.api.BeforeEach;
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
    // 1. GET existing rating → 200 with correct body
    // -----------------------------------------------------------------------
    @Test
    void getByCustomerId_exists_returns200WithBody() throws Exception {
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

    // -----------------------------------------------------------------------
    // 2. GET non-existing rating → 404 with error body
    // -----------------------------------------------------------------------
    @Test
    void getByCustomerId_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/ratings/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(containsString("999")));
    }

    // -----------------------------------------------------------------------
    // 3. GET paginated list → 200 with pagination metadata
    // -----------------------------------------------------------------------
    @Test
    void getAll_withData_returnsPaginatedList() throws Exception {
        insertRating(1L, "90.00", "A", "LOW");
        insertRating(2L, "70.00", "B", "MEDIUM");
        insertRating(3L, "50.00", "C", "HIGH");

        mockMvc.perform(get("/api/v1/ratings?page=0&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.size").value(2));
    }

    // -----------------------------------------------------------------------
    // 4. GET paginated list when empty → 200 with empty content
    // -----------------------------------------------------------------------
    @Test
    void getAll_empty_returnsEmptyPage() throws Exception {
        mockMvc.perform(get("/api/v1/ratings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // -----------------------------------------------------------------------
    // 5. Health endpoint → 200
    // -----------------------------------------------------------------------
    @Test
    void healthEndpoint_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
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
