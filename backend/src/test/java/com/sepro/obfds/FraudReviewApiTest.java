package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Fraud analyst review tests.
 *
 * <p>Covers TC-17, and the review half of TC-15 and FR-15.</p>
 */
class FraudReviewApiTest extends AbstractApiTest {

    /**
     * Creates a high risk transfer: a very large amount sent to a payee added moments ago scores
     * 50 + 25 = 75, which is above the HIGH threshold of 60.
     */
    private JsonNode createHighRiskTransfer(String customerToken) throws Exception {
        Map<String, String> payee = new LinkedHashMap<>();
        payee.put("name", "Unknown Payee");
        payee.put("accountNumber", "400000000555");
        payee.put("bankName", "Example Bank");
        payee.put("ifscCode", "EXMP0000456");
        payee.put("nickname", "New");

        mockMvc.perform(post("/api/beneficiaries")
                        .header(HttpHeaders.AUTHORIZATION, customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payee)))
                .andExpect(status().isCreated());

        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("sourceAccountNumber", CUSTOMER_ACCOUNT);
        transfer.put("destinationAccountNumber", "400000000555");
        transfer.put("amount", "120000.00");
        transfer.put("description", "High risk test transfer");

        String response = mockMvc
                .perform(post("/api/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(transfer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.riskLevel").value("HIGH"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        return parse(response);
    }

    private String firstCaseReference(String analystToken) throws Exception {
        String response = mockMvc
                .perform(get("/api/fraud-cases").header(HttpHeaders.AUTHORIZATION, analystToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return parse(response).get(0).get("reference").asText();
    }

    /** Medium risk transfer whose verification is then failed three times, which blocks it. */
    private String blockTransferByFailedVerification(String customerToken) throws Exception {
        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("sourceAccountNumber", CUSTOMER_ACCOUNT);
        transfer.put("destinationAccountNumber", ESTABLISHED_BENEFICIARY_ACCOUNT);
        transfer.put("amount", "60000.00");
        transfer.put("description", "Verification will fail");

        String reference = parse(mockMvc
                        .perform(post("/api/transactions/transfer")
                                .header(HttpHeaders.AUTHORIZATION, customerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(transfer)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("reference")
                .asText();

        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(post("/api/transactions/" + reference + "/verify")
                            .header(HttpHeaders.AUTHORIZATION, customerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("code", "000000"))))
                    .andExpect(status().isUnprocessableEntity());
        }
        mockMvc.perform(get("/api/transactions/" + reference).header(HttpHeaders.AUTHORIZATION, customerToken))
                .andExpect(jsonPath("$.status").value("BLOCKED"));
        return reference;
    }

    private ResultActions decide(String analystToken, String caseReference, String decision, String remarks)
            throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("decision", decision);
        body.put("remarks", remarks);
        return mockMvc.perform(post("/api/fraud-cases/" + caseReference + "/decision")
                .header(HttpHeaders.AUTHORIZATION, analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    @Test
    @DisplayName("FR-18 A case opened by repeated failed verification can be closed by confirming the block")
    void failedVerificationCaseCanBeClosedByConfirmingTheBlock() throws Exception {
        String customerToken = customerToken();
        String reference = blockTransferByFailedVerification(customerToken);
        String analystToken = analystToken();
        String caseReference = firstCaseReference(analystToken);

        // The case exists for review while the transfer is already final.
        mockMvc.perform(get("/api/fraud-cases/" + caseReference).header(HttpHeaders.AUTHORIZATION, analystToken))
                .andExpect(jsonPath("$.transactionReference").value(reference))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.transactionStatus").value("BLOCKED"));

        // Before the fix this answered 422 INVALID_STATE and the case could never be closed.
        decide(analystToken, caseReference, "BLOCK", "Three failed codes; block confirmed after review.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED_BLOCKED"))
                .andExpect(jsonPath("$.decidedBy").value(ANALYST_USERNAME))
                .andExpect(jsonPath("$.transactionStatus").value("BLOCKED"));

        // Money is untouched and nothing stays reserved.
        mockMvc.perform(get("/api/accounts/" + CUSTOMER_ACCOUNT + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, customerToken))
                .andExpect(jsonPath("$.balance").value(150000.00))
                .andExpect(jsonPath("$.availableBalance").value(150000.00));

        // The closed case cannot be decided a second time.
        decide(analystToken, caseReference, "BLOCK", "Second attempt")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CASE_ALREADY_RESOLVED"));
    }

    @Test
    @DisplayName("FR-22 A transfer blocked by failed verification cannot be released by approving its case")
    void failedVerificationCaseCannotReleaseTheBlockedTransfer() throws Exception {
        String customerToken = customerToken();
        String reference = blockTransferByFailedVerification(customerToken);
        String analystToken = analystToken();
        String caseReference = firstCaseReference(analystToken);

        decide(analystToken, caseReference, "APPROVE", "Trying to release a blocked transfer")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        mockMvc.perform(get("/api/transactions/" + reference).header(HttpHeaders.AUTHORIZATION, customerToken))
                .andExpect(jsonPath("$.status").value("BLOCKED"));
        mockMvc.perform(get("/api/accounts/" + CUSTOMER_ACCOUNT + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, customerToken))
                .andExpect(jsonPath("$.balance").value(150000.00));
    }

    @Test
    @DisplayName("TC-17 An analyst can open a fraud case and see why it was raised (FR-18)")
    void tc17AnalystReviewsCase() throws Exception {
        String customerToken = customerToken();
        JsonNode transaction = createHighRiskTransfer(customerToken);
        String analystToken = analystToken();

        String caseReference = firstCaseReference(analystToken);

        mockMvc.perform(get("/api/fraud-cases/" + caseReference)
                        .header(HttpHeaders.AUTHORIZATION, analystToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionReference").value(transaction.get("reference").asText()))
                .andExpect(jsonPath("$.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.riskScore").value(75))
                .andExpect(jsonPath("$.transactionStatus").value("PENDING"))
                .andExpect(jsonPath("$.riskReason").isNotEmpty())
                .andExpect(jsonPath("$.customerName").value("Ravi Kumar"));
    }

    @Test
    @DisplayName("TC-17 An analyst can take ownership of a case (FR-18)")
    void tc17AnalystAssignsCase() throws Exception {
        createHighRiskTransfer(customerToken());
        String analystToken = analystToken();
        String caseReference = firstCaseReference(analystToken);

        mockMvc.perform(post("/api/fraud-cases/" + caseReference + "/assign")
                        .header(HttpHeaders.AUTHORIZATION, analystToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.assignedTo").value(ANALYST_USERNAME));
    }

    @Test
    @DisplayName("TC-17 Approving a case releases the held transfer and moves the money (FR-15)")
    void tc17ApproveCaseReleasesTransfer() throws Exception {
        String customerToken = customerToken();
        JsonNode transaction = createHighRiskTransfer(customerToken);
        String reference = transaction.get("reference").asText();

        String analystToken = analystToken();
        String caseReference = firstCaseReference(analystToken);

        Map<String, String> decision = new LinkedHashMap<>();
        decision.put("decision", "APPROVE");
        decision.put("remarks", "Customer confirmed the transfer by telephone.");

        mockMvc.perform(post("/api/fraud-cases/" + caseReference + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(decision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED_APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(ANALYST_USERNAME))
                .andExpect(jsonPath("$.transactionStatus").value("APPROVED"));

        mockMvc.perform(get("/api/transactions/" + reference)
                        .header(HttpHeaders.AUTHORIZATION, customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        String balance = mockMvc
                .perform(get("/api/accounts/" + CUSTOMER_ACCOUNT + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, customerToken))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(parse(balance).get("balance").asDouble()).isEqualTo(30000.00);
    }

    @Test
    @DisplayName("TC-17 Blocking a case stops the transfer and leaves the balance untouched (FR-15)")
    void tc17BlockCaseStopsTransfer() throws Exception {
        String customerToken = customerToken();
        JsonNode transaction = createHighRiskTransfer(customerToken);
        String reference = transaction.get("reference").asText();

        String analystToken = analystToken();
        String caseReference = firstCaseReference(analystToken);

        Map<String, String> decision = new LinkedHashMap<>();
        decision.put("decision", "BLOCK");
        decision.put("remarks", "Customer did not recognise this payee.");

        mockMvc.perform(post("/api/fraud-cases/" + caseReference + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(decision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED_BLOCKED"))
                .andExpect(jsonPath("$.transactionStatus").value("BLOCKED"));

        mockMvc.perform(get("/api/transactions/" + reference)
                        .header(HttpHeaders.AUTHORIZATION, customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));

        String balance = mockMvc
                .perform(get("/api/accounts/" + CUSTOMER_ACCOUNT + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, customerToken))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(parse(balance).get("balance").asDouble()).isEqualTo(150000.00);
        assertThat(parse(balance).get("availableBalance").asDouble()).isEqualTo(150000.00);
    }

    @Test
    @DisplayName("TC-17 The same case cannot be decided twice (FR-22)")
    void tc17CaseCannotBeDecidedTwice() throws Exception {
        createHighRiskTransfer(customerToken());
        String analystToken = analystToken();
        String caseReference = firstCaseReference(analystToken);

        Map<String, String> decision = new LinkedHashMap<>();
        decision.put("decision", "BLOCK");
        decision.put("remarks", "Blocked after review.");

        mockMvc.perform(post("/api/fraud-cases/" + caseReference + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(decision)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/fraud-cases/" + caseReference + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(decision)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CASE_ALREADY_RESOLVED"));
    }

    @Test
    @DisplayName("TC-03 A customer cannot reach the fraud analyst endpoints (FR-04)")
    void tc03CustomerCannotReachFraudEndpoints() throws Exception {
        String customerToken = customerToken();

        mockMvc.perform(get("/api/alerts").header(HttpHeaders.AUTHORIZATION, customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/fraud-cases").header(HttpHeaders.AUTHORIZATION, customerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FR-15 A decision must carry remarks so that it can be audited")
    void decisionRequiresRemarks() throws Exception {
        createHighRiskTransfer(customerToken());
        String analystToken = analystToken();
        String caseReference = firstCaseReference(analystToken);

        Map<String, String> decision = new LinkedHashMap<>();
        decision.put("decision", "APPROVE");
        decision.put("remarks", "");

        mockMvc.perform(post("/api/fraud-cases/" + caseReference + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(decision)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.remarks").isNotEmpty());
    }
}
