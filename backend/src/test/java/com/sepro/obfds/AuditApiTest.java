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
 * Audit trail tests. Covers TC-15 (FR-20, NFR-10).
 *
 * <p>Audit rows are written in their own transaction so that they survive even when the operation
 * that produced them was rejected. That is why a failed login still appears in the trail.</p>
 */
class AuditApiTest extends AbstractApiTest {

    @Test
    @DisplayName("TC-15 A successful login is recorded in the audit trail (FR-20)")
    void tc15LoginIsAudited() throws Exception {
        customerToken();

        mockMvc.perform(get("/api/audit")
                        .param("action", "LOGIN_SUCCESS")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.username=='" + CUSTOMER_USERNAME + "')]").isNotEmpty())
                .andExpect(jsonPath("$.content[0].outcome").value("SUCCESS"));
    }

    @Test
    @DisplayName("TC-15 A failed login is also recorded, with outcome FAILURE (FR-20)")
    void tc15FailedLoginIsAudited() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", CUSTOMER_USERNAME, "password", "WrongPass@9"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/audit")
                        .param("action", "LOGIN_FAILURE")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.content[0].outcome").value("FAILURE"))
                .andExpect(jsonPath("$.content[0].username").value(CUSTOMER_USERNAME));
    }

    @Test
    @DisplayName("TC-15 A transfer writes initiation, risk and approval entries (FR-20)")
    void tc15TransferIsAudited() throws Exception {
        String token = customerToken();

        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("sourceAccountNumber", CUSTOMER_ACCOUNT);
        transfer.put("destinationAccountNumber", ESTABLISHED_BENEFICIARY_ACCOUNT);
        transfer.put("amount", "1800.00");
        transfer.put("description", "Audited transfer");

        String created = mockMvc
                .perform(post("/api/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(transfer)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String reference = parse(created).get("reference").asText();
        String admin = adminToken();

        for (String action : new String[] {"TRANSFER_INITIATED", "RISK_EVALUATED", "TRANSACTION_APPROVED"}) {
            mockMvc.perform(get("/api/audit")
                            .param("action", action)
                            .header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.entityReference=='" + reference + "')]").isNotEmpty());
        }
    }

    @Test
    @DisplayName("TC-15 Audit rows record who acted, on what, and with which roles (NFR-10)")
    void tc15AuditRowShape() throws Exception {
        customerToken();

        mockMvc.perform(get("/api/audit")
                        .param("username", CUSTOMER_USERNAME)
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].occurredAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].username").value(CUSTOMER_USERNAME))
                .andExpect(jsonPath("$.content[0].action").isNotEmpty())
                .andExpect(jsonPath("$.content[0].outcome").isNotEmpty());
    }

    @Test
    @DisplayName("TC-03 A customer cannot read the audit trail (FR-04, NFR-10)")
    void tc03CustomerCannotReadAudit() throws Exception {
        mockMvc.perform(get("/api/audit").header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isForbidden());
    }
}
