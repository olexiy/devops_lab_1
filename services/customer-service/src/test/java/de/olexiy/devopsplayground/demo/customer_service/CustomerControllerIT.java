package de.olexiy.devopsplayground.demo.customer_service;

import de.olexiy.devopsplayground.demo.customer_service.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class CustomerControllerIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.3");

    @Autowired WebApplicationContext wac;
    @Autowired CustomerRepository repository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        repository.deleteAll();
    }

    private static final String CREATE_BODY = """
            {
              "firstName": "Anna",
              "lastName": "Müller",
              "email": "anna.mueller@example.com",
              "phone": "+49 30 1234567",
              "dateOfBirth": "1985-04-12"
            }
            """;

    @Nested
    class CreateCustomer {

        @Test
        void create_validRequest_returns201WithBody() throws Exception {
            mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.email").value("anna.mueller@example.com"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        void create_duplicateEmail_returns409() throws Exception {
            mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(containsString("already in use")));
        }

        @Test
        void create_missingLastName_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "firstName": "Anna", "email": "x@example.com",
                                      "dateOfBirth": "1990-01-01" }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Nested
    class GetCustomer {

        @Test
        void getById_existing_returns200() throws Exception {
            var result = mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated())
                    .andReturn();

            String location = result.getResponse().getHeader("Location");

            mockMvc.perform(get(location))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.firstName").value("Anna"))
                    .andExpect(jsonPath("$.lastName").value("Müller"));
        }

        @Test
        void getById_notFound_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/customers/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.path").value("/api/v1/customers/999"));
        }

        @Test
        void getByEmail_existing_returns200() throws Exception {
            mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/customers/email/anna.mueller@example.com"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("anna.mueller@example.com"));
        }

        @Test
        void getByEmail_notFound_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/customers/email/nobody@example.com"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class ListCustomers {

        @Test
        void list_noFilters_returnsPage() throws Exception {
            mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/customers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.page.totalElements").value(1))
                    .andExpect(jsonPath("$.page.page").value(0));
        }

        @Test
        void list_filterByStatus_returnsEmpty() throws Exception {
            mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/customers").param("status", "INACTIVE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0)))
                    .andExpect(jsonPath("$.page.totalElements").value(0));
        }

        @Test
        void list_filterByLastName_returnsMatching() throws Exception {
            mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/customers").param("lastName", "ller"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)));

            mockMvc.perform(get("/api/v1/customers").param("lastName", "zzz"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0)));
        }
    }

    @Nested
    class UpdateCustomer {

        @Test
        void update_validRequest_returns200() throws Exception {
            var result = mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andReturn();
            String location = result.getResponse().getHeader("Location");

            mockMvc.perform(put(location)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "firstName": "Anna",
                                      "lastName": "Schmidt",
                                      "email": "anna.schmidt@example.com",
                                      "dateOfBirth": "1985-04-12"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastName").value("Schmidt"))
                    .andExpect(jsonPath("$.email").value("anna.schmidt@example.com"));
        }

        @Test
        void update_emailTakenByAnother_returns409() throws Exception {
            var result = mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andReturn();
            String loc1 = result.getResponse().getHeader("Location");

            mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "firstName": "Bob", "lastName": "Jones",
                                      "email": "bob@example.com", "dateOfBirth": "1990-01-01" }
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(put(loc1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "firstName": "Anna", "lastName": "Müller",
                                      "email": "bob@example.com", "dateOfBirth": "1985-04-12" }
                                    """))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    class StatusAndDelete {

        @Test
        void updateStatus_validTransition_returns200() throws Exception {
            var result = mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andReturn();
            String location = result.getResponse().getHeader("Location");

            mockMvc.perform(patch(location + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "status": "BLOCKED" }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("BLOCKED"));
        }

        @Test
        void delete_existing_returns204AndStatusIsClosed() throws Exception {
            var result = mockMvc.perform(post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andReturn();
            String location = result.getResponse().getHeader("Location");

            mockMvc.perform(delete(location))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(location))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"));
        }

        @Test
        void delete_notFound_returns404() throws Exception {
            mockMvc.perform(delete("/api/v1/customers/999"))
                    .andExpect(status().isNotFound());
        }
    }
}
