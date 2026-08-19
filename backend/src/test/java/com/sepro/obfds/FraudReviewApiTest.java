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
