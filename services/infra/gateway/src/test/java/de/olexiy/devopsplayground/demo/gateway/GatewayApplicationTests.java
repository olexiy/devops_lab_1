package de.olexiy.devopsplayground.demo.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationTests {

    @LocalServerPort
    int port;

    @Autowired
    RouteDefinitionLocator routeDefinitionLocator;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    // -----------------------------------------------------------------------
    // 1. Context loads
    // -----------------------------------------------------------------------
    @Test
    void contextLoads() {
    }

    // -----------------------------------------------------------------------
    // 2. All four routes are defined in configuration
    // -----------------------------------------------------------------------
    @Test
    void allFourRoutesAreDefined() {
        List<String> routeIds = routeDefinitionLocator.getRouteDefinitions()
                .map(RouteDefinition::getId)
                .collectList()
                .block();

        assertThat(routeIds).containsExactlyInAnyOrder(
                "customer-service",
                "account-service",
                "transaction-service",
                "rating-service"
        );
    }

    // -----------------------------------------------------------------------
    // 3. Health endpoint returns UP
    // -----------------------------------------------------------------------
    @Test
    void healthEndpoint_returnsUp() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    // -----------------------------------------------------------------------
    // 4. Gateway routes actuator endpoint is accessible and returns array
    // -----------------------------------------------------------------------
    @Test
    void gatewayRoutesEndpoint_isAccessible() {
        webTestClient.get().uri("/actuator/gateway/routes")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray();
    }

    // -----------------------------------------------------------------------
    // 5. Unknown path returns 404
    // -----------------------------------------------------------------------
    @Test
    void unknownPath_returns404() {
        webTestClient.get().uri("/api/v1/unknown/path")
                .exchange()
                .expectStatus().isNotFound();
    }
}
