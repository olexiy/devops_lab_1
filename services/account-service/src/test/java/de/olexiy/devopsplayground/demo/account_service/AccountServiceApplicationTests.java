package de.olexiy.devopsplayground.demo.account_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// customer-service.url from test/resources/application.yaml = http://localhost:9876
// No actual customer-service needed — Feign is just configured, not invoked on startup
@SpringBootTest
@Testcontainers
class AccountServiceApplicationTests {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.3");

    @Test
    void contextLoads() {
    }
}
