package com.sepro.obfds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit trail tests. Covers TC-15 (FR-20, NFR-10).
 *
 * <p>Audit rows are written in their own transaction so that they survive even when the operation
 * that produced them was rejected. That is why a failed login still appears in the trail.</p>
 *
 * <p><b>Why this class opts out of the shared test transaction.</b> {@link AbstractApiTest} wraps
 * each test in a transaction that is rolled back. That is invisible on H2 (READ COMMITTED) but on
 * MySQL's default REPEATABLE READ the test transaction pins a snapshot at its first read, so audit
 * rows committed afterwards by the separate audit transaction cannot be seen by the test's own
 * query - which is a property of the test harness, not of the application. Production isolation is
 * deliberately left alone. Instead each test registers its own throw-away customer (so committing
 * real rows cannot disturb the seeded balances other tests rely on) and looks for that customer's
 * rows by username.</p>
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditApiTest extends AbstractApiTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private record Customer(String username, String password, String token) {}

    private Customer registerFreshCustomer() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet();
        Map<String, String> request = new LinkedHashMap<>();
        request.put("username", "audit." + suffix);
        request.put("password", "AuditUser@12345");
        request.put("fullName", "Audit Test " + suffix);
        request.put("email", "audit." + suffix + "@demomail.example");

        String response = mockMvc
                .perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Customer(request.get("username"), request.get("password"),
                "Bearer " + parse(response).get("token").asText());
    }

    @Test
    @DisplayName("TC-15 A successful login is recorded in the audit trail (FR-20)")
    void tc15LoginIsAudited() throws Exception {
        Customer customer = registerFreshCustomer();
        login(customer.username(), customer.password());

        mockMvc.perform(get("/api/audit")
                        .param("action", "LOGIN_SUCCESS")
                        .param("username", customer.username())
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.username=='" + customer.username() + "')]").isNotEmpty())
                .andExpect(jsonPath("$.content[0].outcome").value("SUCCESS"));
    }

    @Test
    @DisplayName("TC-15 A failed login is also recorded, with outcome FAILURE (FR-20)")
    void tc15FailedLoginIsAudited() throws Exception {
        Customer customer = registerFreshCustomer();
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", customer.username(), "password", "WrongPass@9"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/audit")
                        .param("action", "LOGIN_FAILURE")
                        .param("username", customer.username())
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.content[0].outcome").value("FAILURE"))
                .andExpect(jsonPath("$.content[0].username").value(customer.username()));
    }

    @Test
    @DisplayName("TC-15 A transfer writes initiation, risk and approval entries (FR-20)")
    void tc15TransferIsAudited() throws Exception {
        Customer customer = registerFreshCustomer();
        String account = parse(mockMvc.perform(get("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, customer.token()))
                .andReturn().getResponse().getContentAsString()).get(0).get("accountNumber").asText();

        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("sourceAccountNumber", account);
        transfer.put("destinationAccountNumber", ESTABLISHED_BENEFICIARY_ACCOUNT);
        transfer.put("amount", "1800.00");
        transfer.put("description", "Audited transfer");

        String created = mockMvc
                .perform(post("/api/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, customer.token())
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
                            .param("username", customer.username())
                            .header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.entityReference=='" + reference + "')]").isNotEmpty());
        }
    }

    @Test
    @DisplayName("TC-15 Audit rows record who acted, on what, and with which roles (NFR-10)")
    void tc15AuditRowShape() throws Exception {
        Customer customer = registerFreshCustomer();
        login(customer.username(), customer.password());

        mockMvc.perform(get("/api/audit")
                        .param("username", customer.username())
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].occurredAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].username").value(customer.username()))
                .andExpect(jsonPath("$.content[0].action").isNotEmpty())
                .andExpect(jsonPath("$.content[0].outcome").isNotEmpty())
                .andExpect(jsonPath("$.content[0].roles").value("ROLE_CUSTOMER"));
    }

    @Test
    @DisplayName("TC-03 A customer cannot read the audit trail (FR-04, NFR-10)")
    void tc03CustomerCannotReadAudit() throws Exception {
        mockMvc.perform(get("/api/audit").header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isForbidden());
    }
}
