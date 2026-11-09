package com.sepro.obfds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Login abuse controls: bounded throttling that runs before the expensive password hash, and
 * input limits that stop a megabyte-sized "password" from reaching BCrypt.
 *
 * <p>The thresholds are lowered here only so the tests stay fast; the production defaults are in
 * {@code application.yml}.</p>
 */
@TestPropertySource(properties = {
    "obfds.security.login-max-failures=3",
    "obfds.security.login-lockout-seconds=30",
    "spring.datasource.url=jdbc:h2:mem:login-hardening;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class LoginHardeningApiTest extends AbstractApiTest {

    private org.springframework.test.web.servlet.ResultActions attempt(String username, String password)
            throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", username, "password", password))));
    }

    @Test
    @DisplayName("SEC-4 Repeated failures lock that username from that client with 429 and Retry-After")
    void repeatedFailuresAreThrottled() throws Exception {
        for (int i = 0; i < 3; i++) {
            attempt("throttle.target", "Wrong@Password" + i).andExpect(status().isUnauthorized());
        }

        attempt("throttle.target", "Wrong@Password9")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }

    @Test
    @DisplayName("SEC-4 While locked, even the correct password is refused (no bypass by guessing last)")
    void lockoutAppliesToTheCorrectPasswordToo() throws Exception {
        for (int i = 0; i < 3; i++) {
            attempt(CUSTOMER_USERNAME, "Wrong@Password" + i).andExpect(status().isUnauthorized());
        }

        attempt(CUSTOMER_USERNAME, CUSTOMER_PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("SEC-4 A lockout of one username does not affect another username")
    void lockoutIsPerUsername() throws Exception {
        for (int i = 0; i < 3; i++) {
            attempt("victim.one", "Wrong@Password" + i).andExpect(status().isUnauthorized());
        }
        attempt("victim.one", "Wrong@Password9").andExpect(status().isTooManyRequests());

        attempt(SECOND_CUSTOMER_USERNAME, SECOND_CUSTOMER_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-4 A successful login clears the failure count")
    void successResetsTheCounter() throws Exception {
        attempt(ANALYST_USERNAME, "Wrong@1").andExpect(status().isUnauthorized());
        attempt(ANALYST_USERNAME, "Wrong@2").andExpect(status().isUnauthorized());
        attempt(ANALYST_USERNAME, ANALYST_PASSWORD).andExpect(status().isOk());
        attempt(ANALYST_USERNAME, "Wrong@3").andExpect(status().isUnauthorized());
        attempt(ANALYST_USERNAME, "Wrong@4").andExpect(status().isUnauthorized());
        attempt(ANALYST_USERNAME, ANALYST_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-4 An oversized username is rejected as a validation error before authentication")
    void oversizedUsernameIsRejected() throws Exception {
        attempt("u".repeat(500), "Whatever@123").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SEC-4 An oversized password is rejected as a validation error before hashing")
    void oversizedPasswordIsRejected() throws Exception {
        attempt(CUSTOMER_USERNAME, "p".repeat(2000)).andExpect(status().isBadRequest());
    }
}
