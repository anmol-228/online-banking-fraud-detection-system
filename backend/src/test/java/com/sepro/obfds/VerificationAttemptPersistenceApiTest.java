package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves that a failed verification attempt is actually written down (FR-14, FR-15, NFR-01).
 *
 * <p>This class deliberately does <strong>not</strong> extend {@link AbstractApiTest}, and the
 * reason is the whole point of the test. {@code AbstractApiTest} wraps every test method in a
 * single transaction that is rolled back at the end, which means each method also shares one
 * Hibernate persistence context. A counter incremented on a managed entity is therefore visible
 * to a later read in the same method whether or not it was ever committed — so the attempt-limit
 * tests in {@link TransactionWorkflowApiTest} passed against a build in which the limit did not
 * work at all. Running outside a test-managed transaction is what makes the difference between
 * "the object changed" and "the database changed" observable.</p>
 *
 * <p>The defect this pins down: {@code submitVerification} increments the attempt counter and
 * then reports the wrong code by throwing, and under Spring's default rollback-on-runtime-
 * exception rule the increment was discarded with the exception. Against a real HTTP client the
 * counter stayed at zero no matter how many codes were tried, the three-attempt block never
 * fired, and the FAILED status the {@code REPEATED_FAILED_VERIFICATION} fraud rule reads was
 * never stored either. The fix is {@code noRollbackFor = BusinessRuleException.class}.</p>
 *
 * <p>Because nothing here is rolled back, the test works on a customer it registers itself rather
 * than on a seeded one, and {@link DirtiesContext} rebuilds the context afterwards so that later
 * test classes still see the untouched seeded dataset — {@code AdministrationApiTest} in
 * particular asserts an exact seeded account count.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VerificationAttemptPersistenceApiTest {

    /** Registration opens the first account with a balance of 25,000.00 (see AuthService). */
    private static final String TRANSFER_AMOUNT = "21000.00";

    /**
     * Not a saved payee and not an account inside the simulation, so the score is exactly
     * UNREGISTERED_PAYEE (20) + BALANCE_DRAIN (15) = 35, which is MEDIUM and therefore held for
     * verification. The unusual-hour rule is switched off in the test profile, so the score does
     * not depend on the time of day the suite runs.
     */
    private static final String DESTINATION_ACCOUNT = "400000000188";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName(
            "FR-14, FR-15: a wrong verification code is recorded durably and the third one blocks "
                    + "the transfer")
    void failedVerificationAttemptsSurviveTheRejectionResponse() throws Exception {
        String token = registerCustomer();
        String account = firstAccountNumber(token);
        String reference = initiateHeldTransfer(token, account);

        // The transfer really is waiting for a code, with a full allowance of attempts.
        assertThat(verificationState(token, reference).get("attempts").asInt()).isZero();
        assertThat(verificationState(token, reference).get("attemptsRemaining").asInt()).isEqualTo(3);

        submitWrongCode(token, reference, "INVALID_VERIFICATION_CODE");

        // The assertion that fails without the fix: re-read from the database, in a request of its
        // own, rather than from an entity still managed by the transaction that changed it.
        JsonNode afterOneAttempt = verificationState(token, reference);
        assertThat(afterOneAttempt.get("attempts").asInt())
                .as("a rejected code must leave the attempt counter incremented in the database, "
                        + "not only in the response message")
                .isEqualTo(1);
        assertThat(afterOneAttempt.get("attemptsRemaining").asInt()).isEqualTo(2);

        submitWrongCode(token, reference, "INVALID_VERIFICATION_CODE");
        assertThat(verificationState(token, reference).get("attempts").asInt()).isEqualTo(2);

        // The third wrong code exhausts the allowance and must block the transfer for good.
        submitWrongCode(token, reference, "VERIFICATION_FAILED");

        mockMvc.perform(get("/api/transactions/" + reference).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));

        assertThat(verificationState(token, reference).get("status").asText()).isEqualTo("FAILED");

        // A blocked transfer moves no money and leaves nothing reserved.
        JsonNode balance = balance(token, account);
        assertThat(balance.get("balance").asDouble()).isEqualTo(25000.00);
        assertThat(balance.get("availableBalance").asDouble()).isEqualTo(25000.00);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String registerCustomer() throws Exception {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("username", "verify.persistence");
        request.put("password", "VerifyPersist@123");
        request.put("fullName", "Priya Deshmukh");
        request.put("email", "priya.deshmukh@demomail.example");
        request.put("phone", "9876500188");
        request.put("address", "22 Residency Road, Pune, Maharashtra");

        String response = mockMvc
                .perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return "Bearer " + objectMapper.readTree(response).get("token").asText();
    }

    private String firstAccountNumber(String token) throws Exception {
        String response = mockMvc
                .perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get(0).get("accountNumber").asText();
    }

    private String initiateHeldTransfer(String token, String account) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceAccountNumber", account);
        payload.put("destinationAccountNumber", DESTINATION_ACCOUNT);
        payload.put("amount", TRANSFER_AMOUNT);
        payload.put("description", "Verification attempt persistence test");

        String response = mockMvc
                .perform(post("/api/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.verificationRequired").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("reference").asText();
    }

    /** Submits a code that is never the issued one, and asserts the error the API reports. */
    private void submitWrongCode(String token, String reference, String expectedCode) throws Exception {
        mockMvc.perform(post("/api/transactions/" + reference + "/verify")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "000000"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    private JsonNode verificationState(String token, String reference) throws Exception {
        String response = mockMvc
                .perform(get("/api/transactions/" + reference + "/verification")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode balance(String token, String accountNumber) throws Exception {
        String response = mockMvc
                .perform(get("/api/accounts/" + accountNumber + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
