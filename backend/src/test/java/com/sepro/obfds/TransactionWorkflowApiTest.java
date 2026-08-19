package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * End-to-end tests of the transaction and fraud workflow.
 *
 * <p>Covers TC-06, TC-07, TC-09, TC-10, TC-11, TC-12, TC-13, TC-14, TC-16 and TC-18.</p>
 */
class TransactionWorkflowApiTest extends AbstractApiTest {

    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> transfer(String destination, String amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceAccountNumber", CUSTOMER_ACCOUNT);
        payload.put("destinationAccountNumber", destination);
        payload.put("amount", amount);
        payload.put("description", "Automated test transfer");
        return payload;
    }

    private JsonNode postTransfer(String token, Map<String, Object> payload, int expectedStatus)
            throws Exception {
        String response = mockMvc
                .perform(post("/api/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return parse(response);
    }

    private String addBeneficiary(String token, String accountNumber) throws Exception {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("name", "Nikhil Rao");
        payload.put("accountNumber", accountNumber);
        payload.put("bankName", "Example Bank");
        payload.put("ifscCode", "EXMP0000456");
        payload.put("nickname", "Test payee");

        mockMvc.perform(post("/api/beneficiaries")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated());
        return accountNumber;
    }

    /**
     * Reads the verification code out of the notification that the workflow sent to the customer.
     *
     * <p>This mirrors exactly what a person does during the demonstration: open the notifications
     * page and read the code that was delivered there.</p>
     */
    private String readVerificationCode(String token, String transactionReference) throws Exception {
        String response = mockMvc
                .perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (JsonNode notification : parse(response)) {
            boolean matches = "VERIFICATION".equals(notification.get("type").asText())
                    && transactionReference.equals(notification.get("relatedReference").asText());
            if (matches) {
                Matcher matcher = SIX_DIGITS.matcher(notification.get("message").asText());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        throw new AssertionError("No verification code was delivered for " + transactionReference);
    }

    private JsonNode balance(String token, String accountNumber) throws Exception {
        String response = mockMvc
                .perform(get("/api/accounts/" + accountNumber + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return parse(response);
    }

    // ------------------------------------------------------------------
    // TC-06 Validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TC-06 A transfer with an invalid amount is rejected by validation (FR-09)")
    void tc06InvalidAmountRejected() throws Exception {
        mockMvc.perform(post("/api/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "-500.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.amount").isNotEmpty());
    }

    @Test
    @DisplayName("TC-06 A transfer larger than the available balance is rejected (FR-09)")
    void tc06InsufficientBalanceRejected() throws Exception {
        mockMvc.perform(post("/api/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "900000.00"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    @DisplayName("TC-06 A transfer to the same account is rejected (FR-09)")
    void tc06SameAccountRejected() throws Exception {
        mockMvc.perform(post("/api/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(transfer(CUSTOMER_ACCOUNT, "1000.00"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
    }

    @Test
    @DisplayName("TC-06 A customer cannot transfer from an account they do not own (NFR-08)")
    void tc06ForeignSourceAccountRejected() throws Exception {
        Map<String, Object> payload = transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "1000.00");
        payload.put("sourceAccountNumber", SECOND_CUSTOMER_ACCOUNT);

        mockMvc.perform(post("/api/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // TC-07 Low risk transfer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TC-07 A low risk transfer is approved and both balances change (FR-07, NFR-02)")
    void tc07LowRiskTransferIsApproved() throws Exception {
        String token = customerToken();

        JsonNode result = postTransfer(token, transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "2500.00"), 201);

        assertThat(result.get("status").asText()).isEqualTo("APPROVED");
        assertThat(result.get("riskLevel").asText()).isEqualTo("LOW");
        assertThat(result.get("riskScore").asInt()).isZero();
        assertThat(result.get("verificationRequired").asBoolean()).isFalse();
        assertThat(result.get("completedAt").isNull()).isFalse();

        // The money left the source account.
        assertThat(balance(token, CUSTOMER_ACCOUNT).get("balance").asDouble()).isEqualTo(147500.00);

        // The money arrived in the destination account, which also belongs to this simulation.
        String secondToken = login(SECOND_CUSTOMER_USERNAME, SECOND_CUSTOMER_PASSWORD);
        assertThat(balance(secondToken, SECOND_CUSTOMER_ACCOUNT).get("balance").asDouble())
                .isEqualTo(122500.00);
    }

    @Test
    @DisplayName("TC-13 An approved transfer notifies the customer (FR-16)")
    void tc13TransferNotification() throws Exception {
        String token = customerToken();
        JsonNode result = postTransfer(token, transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "1500.00"), 201);
        String reference = result.get("reference").asText();

        mockMvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.relatedReference=='" + reference + "')]").isNotEmpty())
                .andExpect(jsonPath("$[0].title").value("Transfer completed"))
                .andExpect(jsonPath("$[0].type").value("TRANSACTION"))
                .andExpect(jsonPath("$[0].read").value(false));
    }

    @Test
    @DisplayName("TC-16 A completed transfer appears in the transaction history (FR-10)")
    void tc16TransactionHistory() throws Exception {
        String token = customerToken();
        JsonNode result = postTransfer(token, transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "1200.00"), 201);
        String reference = result.get("reference").asText();

        mockMvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reference").value(reference))
                .andExpect(jsonPath("$.content[0].status").value("APPROVED"));

        mockMvc.perform(get("/api/transactions/" + reference).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(1200.00));
    }

    @Test
    @DisplayName("TC-16 A customer cannot read a transaction belonging to somebody else (NFR-08)")
    void tc16CannotReadForeignTransaction() throws Exception {
        JsonNode result = postTransfer(customerToken(), transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "900.00"), 201);
        String reference = result.get("reference").asText();

        String otherToken = login(SECOND_CUSTOMER_USERNAME, SECOND_CUSTOMER_PASSWORD);
        mockMvc.perform(get("/api/transactions/" + reference).header(HttpHeaders.AUTHORIZATION, otherToken))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // TC-09, TC-10, TC-11, TC-12 Medium risk and additional verification
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TC-10 A medium risk transfer is held for additional verification (FR-12, FR-14)")
    void tc10MediumRiskTransferRequestsVerification() throws Exception {
        String token = customerToken();

        JsonNode result = postTransfer(token, transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "60000.00"), 201);

        assertThat(result.get("riskLevel").asText()).isEqualTo("MEDIUM");
        assertThat(result.get("riskScore").asInt()).isEqualTo(35);
        assertThat(result.get("status").asText()).isEqualTo("PENDING_VERIFICATION");
        assertThat(result.get("verificationRequired").asBoolean()).isTrue();
        assertThat(result.get("riskReason").asText()).contains("high limit");

        String reference = result.get("reference").asText();
        mockMvc.perform(get("/api/transactions/" + reference + "/verification")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptsRemaining").value(3));
    }

    @Test
    @DisplayName("TC-09 A suspicious transfer raises a fraud alert visible to the analyst (FR-13)")
    void tc09FraudAlertIsGenerated() throws Exception {
        JsonNode result = postTransfer(customerToken(), transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "60000.00"), 201);
        String reference = result.get("reference").asText();

        mockMvc.perform(get("/api/alerts").header(HttpHeaders.AUTHORIZATION, analystToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].transactionReference").value(reference))
                .andExpect(jsonPath("$[0].riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$[0].riskScore").value(35))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].reason").isNotEmpty());
    }

    @Test
    @DisplayName("TC-18 A held transfer reserves funds without moving them yet (FR-22, NFR-09)")
    void tc18HeldTransferReservesFunds() throws Exception {
        String token = customerToken();
        postTransfer(token, transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "60000.00"), 201);

        JsonNode balance = balance(token, CUSTOMER_ACCOUNT);
        assertThat(balance.get("balance").asDouble()).isEqualTo(150000.00);
        assertThat(balance.get("reservedAmount").asDouble()).isEqualTo(60000.00);
        assertThat(balance.get("availableBalance").asDouble()).isEqualTo(90000.00);
    }

    @Test
    @DisplayName("TC-11 A correct verification code releases the transfer (FR-14, FR-15)")
    void tc11SuccessfulVerification() throws Exception {
        String token = customerToken();
        JsonNode initiated = postTransfer(token, transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "60000.00"), 201);
        String reference = initiated.get("reference").asText();

        String code = readVerificationCode(token, reference);

        mockMvc.perform(post("/api/transactions/" + reference + "/verify")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.verificationRequired").value(false));

        // Now, and only now, the money has actually moved.
        JsonNode balance = balance(token, CUSTOMER_ACCOUNT);
        assertThat(balance.get("balance").asDouble()).isEqualTo(90000.00);
        assertThat(balance.get("reservedAmount").asDouble()).isZero();
    }

    @Test
    @DisplayName("TC-12 A wrong code is rejected and the attempt counter decreases (FR-14)")
    void tc12WrongCodeIsRejected() throws Exception {
        String token = customerToken();
        JsonNode initiated = postTransfer(token, transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "60000.00"), 201);
        String reference = initiated.get("reference").asText();

        mockMvc.perform(post("/api/transactions/" + reference + "/verify")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", "000000"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));

        mockMvc.perform(get("/api/transactions/" + reference + "/verification")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.attemptsRemaining").value(2));
    }

    @Test
    @DisplayName("TC-12 Using up every attempt blocks the transfer and leaves the money alone (FR-15)")
    void tc12FailedVerificationBlocksTransaction() throws Exception {
        String token = customerToken();
        JsonNode initiated = postTransfer(token, transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "60000.00"), 201);
        String reference = initiated.get("reference").asText();

        for (int attempt = 1; attempt <= 2; attempt++) {
            mockMvc.perform(post("/api/transactions/" + reference + "/verify")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("code", "000000"))))
                    .andExpect(status().isUnprocessableEntity());
        }

        mockMvc.perform(post("/api/transactions/" + reference + "/verify")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", "000000"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERIFICATION_FAILED"));

        mockMvc.perform(get("/api/transactions/" + reference).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));

        // A blocked transfer must not move money and must not keep funds reserved.
        JsonNode balance = balance(token, CUSTOMER_ACCOUNT);
        assertThat(balance.get("balance").asDouble()).isEqualTo(150000.00);
        assertThat(balance.get("availableBalance").asDouble()).isEqualTo(150000.00);
    }

    @Test
    @DisplayName("TC-18 A completed transfer cannot be verified again (FR-22)")
    void tc18NoVerificationOnCompletedTransfer() throws Exception {
        String token = customerToken();
        JsonNode approved = postTransfer(token, transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "2000.00"), 201);
        String reference = approved.get("reference").asText();

        mockMvc.perform(post("/api/transactions/" + reference + "/verify")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", "123456"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    // ------------------------------------------------------------------
    // High risk
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FR-12 A very large transfer to a brand new payee is held for fraud review")
    void highRiskTransferIsHeldForReview() throws Exception {
        String token = customerToken();
        String newPayee = addBeneficiary(token, "400000000123");

        JsonNode result = postTransfer(token, transfer(newPayee, "120000.00"), 201);

        assertThat(result.get("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(result.get("riskScore").asInt()).isEqualTo(75);
        assertThat(result.get("status").asText()).isEqualTo("PENDING");
        assertThat(result.get("verificationRequired").asBoolean()).isFalse();

        mockMvc.perform(get("/api/fraud-cases").header(HttpHeaders.AUTHORIZATION, analystToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].riskLevel").value("HIGH"));
    }

    @Test
    @DisplayName("FR-11 A transfer is not counted against itself by the velocity rule")
    void transferIsNotCountedAgainstItself() throws Exception {
        String token = customerToken();

        // The velocity rule fires at three transfers inside the window. Three small transfers to
        // an established payee must therefore still be scored LOW: only the two earlier transfers
        // count as recent activity when the third one is assessed.
        String[] amounts = {"1100.00", "1200.00", "1300.00"};
        JsonNode last = null;
        for (String amount : amounts) {
            last = postTransfer(token, transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, amount), 201);
        }

        assertThat(last.get("riskScore").asInt()).isZero();
        assertThat(last.get("riskLevel").asText()).isEqualTo("LOW");
        assertThat(last.get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("FR-11 A fourth rapid transfer does trigger the velocity rule")
    void fourthRapidTransferIsFlagged() throws Exception {
        String token = customerToken();

        String[] amounts = {"1100.00", "1200.00", "1300.00", "1400.00"};
        JsonNode last = null;
        for (String amount : amounts) {
            last = postTransfer(token, transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, amount), 201);
        }

        // Three earlier transfers are now inside the window, so the rule fires on the fourth.
        assertThat(last.get("riskScore").asInt()).isEqualTo(20);
        assertThat(last.get("riskReason").asText()).contains("transfers in the last");
        assertThat(last.get("riskLevel").asText()).isEqualTo("LOW");
    }

    // ------------------------------------------------------------------
    // TC-14 Duplicate protection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TC-14 An identical transfer repeated at once is rejected as a duplicate (NFR-09)")
    void tc14DuplicateTransferRejected() throws Exception {
        String token = customerToken();
        postTransfer(token, transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "3000.00"), 201);

        mockMvc.perform(post("/api/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "3000.00"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_TRANSACTION"));
    }

    @Test
    @DisplayName("TC-14 Re-sending the same request with the same key returns the first transfer")
    void tc14IdempotencyKeyReturnsOriginal() throws Exception {
        String token = customerToken();

        Map<String, Object> payload = transfer(ESTABLISHED_BENEFICIARY_ACCOUNT, "4000.00");
        payload.put("idempotencyKey", "test-key-0001");

        JsonNode first = postTransfer(token, payload, 201);
        JsonNode second = postTransfer(token, payload, 201);

        assertThat(second.get("reference").asText()).isEqualTo(first.get("reference").asText());

        // Exactly one transaction exists and the money moved exactly once.
        mockMvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        assertThat(balance(token, CUSTOMER_ACCOUNT).get("balance").asDouble()).isEqualTo(146000.00);
    }
}
