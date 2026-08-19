package com.sepro.obfds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared setup for the integration tests.
 *
 * <p>Each test method runs inside a transaction that is rolled back afterwards, so the seeded
 * demo dataset is identical at the start of every test and the tests can run in any order.</p>
 *
 * <p>The demo accounts referenced by the constants below are created by {@code DataSeeder}. They
 * are fictional and exist only inside this simulation.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class AbstractApiTest {

    protected static final String CUSTOMER_USERNAME = "ravi.kumar";
    protected static final String CUSTOMER_PASSWORD = "Customer@123";
    protected static final String CUSTOMER_ACCOUNT = "900000000001";

    protected static final String SECOND_CUSTOMER_USERNAME = "meera.nair";
    protected static final String SECOND_CUSTOMER_PASSWORD = "Customer@123";
    protected static final String SECOND_CUSTOMER_ACCOUNT = "900000000003";

    protected static final String ADMIN_USERNAME = "admin.bank";
    protected static final String ADMIN_PASSWORD = "Admin@123";

    protected static final String ANALYST_USERNAME = "analyst.fraud";
    protected static final String ANALYST_PASSWORD = "Analyst@123";

    protected static final String OFFICER_USERNAME = "ops.officer";
    protected static final String OFFICER_PASSWORD = "Officer@123";

    /** A payee of ravi.kumar that was seeded 30 days ago, so it is not a new beneficiary. */
    protected static final String ESTABLISHED_BENEFICIARY_ACCOUNT = "900000000003";

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;

    /** Logs in and returns the value to put in the Authorization header. */
    protected String login(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(
                new java.util.LinkedHashMap<>(
                        java.util.Map.of("username", username, "password", password)));

        String response = mockMvc
                .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        return "Bearer " + json.get("token").asText();
    }

    protected String customerToken() throws Exception {
        return login(CUSTOMER_USERNAME, CUSTOMER_PASSWORD);
    }

    protected String analystToken() throws Exception {
        return login(ANALYST_USERNAME, ANALYST_PASSWORD);
    }

    protected String adminToken() throws Exception {
        return login(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    protected JsonNode parse(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
