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
 * Complaint and dispute tests. Covers TC-22 (FR-17).
 *
 * <p>TC-22 was added after TC-01 to TC-20 to cover the dispute workflow.</p>
 */
class DisputeApiTest extends AbstractApiTest {

    private String createTransaction(String token) throws Exception {
        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("sourceAccountNumber", CUSTOMER_ACCOUNT);
        transfer.put("destinationAccountNumber", ESTABLISHED_BENEFICIARY_ACCOUNT);
        transfer.put("amount", "2200.00");
        transfer.put("description", "Transfer that will be disputed");

        String created = mockMvc
                .perform(post("/api/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(transfer)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return parse(created).get("reference").asText();
    }

    private Map<String, String> disputePayload(String transactionReference) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("transactionReference", transactionReference);
        payload.put("subject", "I did not authorise this transfer");
        payload.put("description", "This transfer appeared on my account and I did not make it.");
        return payload;
    }

    @Test
    @DisplayName("TC-22 A customer can raise a complaint about their own transaction (FR-17)")
    void tc22SubmitDispute() throws Exception {
        String token = customerToken();
        String reference = createTransaction(token);

        String created = mockMvc
                .perform(post("/api/disputes")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(disputePayload(reference))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.transactionReference").value(reference))
                .andExpect(jsonPath("$.reference").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String disputeReference = parse(created).get("reference").asText();

        // The customer is notified that the complaint was received (FR-16).
        mockMvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.relatedReference=='" + disputeReference + "')]").isNotEmpty());

        mockMvc.perform(get("/api/disputes").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("TC-22 The same transaction cannot carry two open complaints (NFR-09)")
    void tc22DuplicateDisputeRejected() throws Exception {
        String token = customerToken();
        String reference = createTransaction(token);

        mockMvc.perform(post("/api/disputes")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(disputePayload(reference))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/disputes")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(disputePayload(reference))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_ALREADY_OPEN"));
    }

    @Test
    @DisplayName("TC-22 A customer cannot dispute a transaction that is not theirs (NFR-08)")
    void tc22CannotDisputeForeignTransaction() throws Exception {
        String reference = createTransaction(customerToken());
        String otherToken = login(SECOND_CUSTOMER_USERNAME, SECOND_CUSTOMER_PASSWORD);

        mockMvc.perform(post("/api/disputes")
                        .header(HttpHeaders.AUTHORIZATION, otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(disputePayload(reference))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("TC-22 An operations officer works the queue and resolves the complaint (FR-17)")
    void tc22OfficerResolvesDispute() throws Exception {
        String token = customerToken();
        String reference = createTransaction(token);

        String created = mockMvc
                .perform(post("/api/disputes")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(disputePayload(reference))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String disputeReference = parse(created).get("reference").asText();
        String officerToken = login(OFFICER_USERNAME, OFFICER_PASSWORD);

        mockMvc.perform(get("/api/disputes/queue").header(HttpHeaders.AUTHORIZATION, officerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reference").value(disputeReference));

        Map<String, String> resolution = new LinkedHashMap<>();
        resolution.put("status", "RESOLVED");
        resolution.put("resolution", "The transfer was confirmed as genuine after speaking to the customer.");

        mockMvc.perform(post("/api/disputes/" + disputeReference + "/resolve")
                        .header(HttpHeaders.AUTHORIZATION, officerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(resolution)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.handledBy").value(OFFICER_USERNAME));

        mockMvc.perform(get("/api/disputes").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$[0].resolution").isNotEmpty());
    }

    @Test
    @DisplayName("TC-03 A customer cannot open the operations dispute queue (FR-04)")
    void tc03CustomerCannotOpenQueue() throws Exception {
        mockMvc.perform(get("/api/disputes/queue").header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FR-17 A complaint with too short a description is rejected")
    void shortDescriptionRejected() throws Exception {
        String token = customerToken();
        String reference = createTransaction(token);

        Map<String, String> payload = disputePayload(reference);
        payload.put("description", "bad");

        mockMvc.perform(post("/api/disputes")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.description").isNotEmpty());
    }
}
