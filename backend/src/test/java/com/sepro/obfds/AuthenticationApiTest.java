package com.sepro.obfds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Authentication and authorization tests.
 *
 * <p>Covers TC-01, TC-02, TC-03 and TC-20, and the registration part of FR-01.</p>
 */
class AuthenticationApiTest extends AbstractApiTest {

    @Test
    @DisplayName("TC-01 Valid login returns a token and the roles of the user (FR-02, FR-03)")
    void tc01ValidLogin() throws Exception {
        String body = json(Map.of("username", CUSTOMER_USERNAME, "password", CUSTOMER_PASSWORD));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value(CUSTOMER_USERNAME))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.customerNumber").isNotEmpty());
    }

    @Test
    @DisplayName("TC-02 Invalid login is rejected with 401 and no token (FR-02, NFR-01)")
    void tc02InvalidLogin() throws Exception {
        String body = json(Map.of("username", CUSTOMER_USERNAME, "password", "WrongPassword@1"));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    @DisplayName("TC-02 Login with an unknown username gives the same generic message (NFR-01)")
    void tc02UnknownUsername() throws Exception {
        String body = json(Map.of("username", "no.such.user", "password", "Whatever@123"));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password."));
    }

    @Test
    @DisplayName("TC-03 A customer may not reach an administrator endpoint (FR-04)")
    void tc03WrongRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("TC-03 A fraud analyst may not reach customer banking endpoints (FR-04)")
    void tc03AnalystCannotUseCustomerEndpoints() throws Exception {
        mockMvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, analystToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TC-20 A protected endpoint rejects a request with no token (FR-03)")
    void tc20UnauthenticatedAccessIsRejected() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("TC-20 A protected endpoint rejects a forged or malformed token (NFR-01)")
    void tc20TamperedTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FR-01 A new customer can register and immediately receives an account")
    void registrationCreatesCustomerAndAccount() throws Exception {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("username", "new.customer");
        request.put("password", "NewCustomer@123");
        request.put("fullName", "Sanjay Patel");
        request.put("email", "sanjay.patel@demomail.example");
        request.put("phone", "9876500099");
        request.put("address", "7 Nehru Street, Surat, Gujarat");

        String response = mockMvc
                .perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = "Bearer " + parse(response).get("token").asText();

        mockMvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("FR-01 Registration rejects a username that is already taken")
    void registrationRejectsDuplicateUsername() throws Exception {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("username", CUSTOMER_USERNAME);
        request.put("password", "AnotherPass@123");
        request.put("fullName", "Duplicate Person");
        request.put("email", "duplicate.person@demomail.example");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    @DisplayName("FR-01 Registration rejects a weak or malformed submission (NFR-01)")
    void registrationValidatesInput() throws Exception {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("username", "ab");
        request.put("password", "short");
        request.put("fullName", "");
        request.put("email", "not-an-email");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.username").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.password").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.email").isNotEmpty());
    }

    @Test
    @DisplayName("FR-03 The profile endpoint returns the identity of the caller")
    void profileReturnsCaller() throws Exception {
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(CUSTOMER_USERNAME))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }
}
