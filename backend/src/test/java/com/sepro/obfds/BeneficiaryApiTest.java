package com.sepro.obfds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** Beneficiary management tests. Covers TC-05 (FR-08). */
class BeneficiaryApiTest extends AbstractApiTest {

    private Map<String, String> beneficiaryPayload(String accountNumber) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("name", "Nikhil Rao");
        payload.put("accountNumber", accountNumber);
        payload.put("bankName", "Example Bank");
        payload.put("ifscCode", "EXMP0000456");
        payload.put("nickname", "Colleague");
        return payload;
    }

    @Test
    @DisplayName("TC-05 A customer can add a beneficiary and see it in their list (FR-08)")
    void tc05AddBeneficiary() throws Exception {
        String token = customerToken();

        mockMvc.perform(post("/api/beneficiaries")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(beneficiaryPayload("400000000077"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Nikhil Rao"))
                .andExpect(jsonPath("$.accountNumber").value("400000000077"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        mockMvc.perform(get("/api/beneficiaries").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountNumber").value("400000000077"));
    }

    @Test
    @DisplayName("TC-05 Adding the same beneficiary twice is rejected (NFR-09)")
    void tc05DuplicateBeneficiaryRejected() throws Exception {
        String token = customerToken();

        mockMvc.perform(post("/api/beneficiaries")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(beneficiaryPayload("400000000088"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/beneficiaries")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(beneficiaryPayload("400000000088"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BENEFICIARY_EXISTS"));
    }

    @Test
    @DisplayName("FR-08 A customer cannot add one of their own accounts as a beneficiary")
    void ownAccountRejected() throws Exception {
        mockMvc.perform(post("/api/beneficiaries")
                        .header(HttpHeaders.AUTHORIZATION, customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(beneficiaryPayload(CUSTOMER_ACCOUNT))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OWN_ACCOUNT_AS_BENEFICIARY"));
    }

    @Test
    @DisplayName("FR-08 An invalid account number is rejected by validation")
    void invalidAccountNumberRejected() throws Exception {
        mockMvc.perform(post("/api/beneficiaries")
                        .header(HttpHeaders.AUTHORIZATION, customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(beneficiaryPayload("12ab"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.accountNumber").isNotEmpty());
    }

    @Test
    @DisplayName("FR-08 A removed beneficiary disappears from the list")
    void removeBeneficiary() throws Exception {
        String token = customerToken();

        String created = mockMvc
                .perform(post("/api/beneficiaries")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(beneficiaryPayload("400000000099"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long id = parse(created).get("id").asLong();

        mockMvc.perform(delete("/api/beneficiaries/" + id).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/beneficiaries").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.accountNumber=='400000000099')]").isEmpty());
    }
}
