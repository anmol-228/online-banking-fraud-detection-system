package com.sepro.obfds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Administration tests. Covers TC-21 (FR-19).
 *
 * <p>TC-21 was added after TC-01 to TC-20 to cover user and role management, which the earlier
 * test identifiers did not reach.</p>
 */
class AdministrationApiTest extends AbstractApiTest {

    private long userId(String adminToken, String username) throws Exception {
        String response = mockMvc
                .perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (JsonNode user : parse(response)) {
            if (username.equals(user.get("username").asText())) {
                return user.get("id").asLong();
            }
        }
        throw new AssertionError("User not found in administration list: " + username);
    }

    @Test
    @DisplayName("TC-21 An administrator can list every user with their roles (FR-19)")
    void tc21ListUsers() throws Exception {
        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[?(@.username=='" + CUSTOMER_USERNAME + "')].roles[0]")
                        .value("CUSTOMER"))
                .andExpect(jsonPath("$[?(@.username=='" + ANALYST_USERNAME + "')].enabled").value(true));
    }

    @Test
    @DisplayName("TC-21 An administrator can grant an extra role to a user (FR-19)")
    void tc21GrantRole() throws Exception {
        String admin = adminToken();
        long officerId = userId(admin, OFFICER_USERNAME);

        mockMvc.perform(put("/api/admin/users/" + officerId + "/roles")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roles", List.of("OPS_OFFICER", "FRAUD_ANALYST")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2))
                .andExpect(jsonPath("$.roles[0]").value("FRAUD_ANALYST"))
                .andExpect(jsonPath("$.roles[1]").value("OPS_OFFICER"));
    }

    @Test
    @DisplayName("TC-21 A newly granted role takes effect on the next login (FR-04, FR-19)")
    void tc21GrantedRoleChangesAccess() throws Exception {
        String admin = adminToken();
        long officerId = userId(admin, OFFICER_USERNAME);

        // Before the change the operations officer may not read fraud alerts.
        String officerToken = login(OFFICER_USERNAME, OFFICER_PASSWORD);
        mockMvc.perform(get("/api/alerts").header(HttpHeaders.AUTHORIZATION, officerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/users/" + officerId + "/roles")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roles", List.of("OPS_OFFICER", "FRAUD_ANALYST")))))
                .andExpect(status().isOk());

        // After logging in again the new role is present in the token.
        String upgradedToken = login(OFFICER_USERNAME, OFFICER_PASSWORD);
        mockMvc.perform(get("/api/alerts").header(HttpHeaders.AUTHORIZATION, upgradedToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("TC-21 An administrator can disable and re-enable a login (FR-19)")
    void tc21DisableUser() throws Exception {
        String admin = adminToken();
        long customerId = userId(admin, CUSTOMER_USERNAME);

        mockMvc.perform(put("/api/admin/users/" + customerId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(put("/api/admin/users/" + customerId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @DisplayName("TC-21 An administrator cannot remove their own administrator role (FR-19)")
    void tc21CannotRemoveOwnAdminRole() throws Exception {
        String admin = adminToken();
        long adminId = userId(admin, ADMIN_USERNAME);

        mockMvc.perform(put("/api/admin/users/" + adminId + "/roles")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roles", List.of("CUSTOMER")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CANNOT_REMOVE_OWN_ADMIN_ROLE"));
    }

    @Test
    @DisplayName("TC-21 An administrator cannot disable their own login (FR-19)")
    void tc21CannotDisableSelf() throws Exception {
        String admin = adminToken();
        long adminId = userId(admin, ADMIN_USERNAME);

        mockMvc.perform(put("/api/admin/users/" + adminId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("enabled", false))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CANNOT_DISABLE_SELF"));
    }

    @Test
    @DisplayName("TC-23 Staff can generate operational and fraud reports (FR-21)")
    void tc23Reports() throws Exception {
        String admin = adminToken();

        mockMvc.perform(get("/api/reports/operational").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCustomers").value(2))
                .andExpect(jsonPath("$.totalAccounts").value(3))
                .andExpect(jsonPath("$.generatedAt").isNotEmpty());

        mockMvc.perform(get("/api/reports/fraud").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAlerts").value(0))
                .andExpect(jsonPath("$.detectionRatePercent").value(0.0));
    }

    @Test
    @DisplayName("TC-03 A customer cannot generate staff reports (FR-04)")
    void tc03CustomerCannotReadReports() throws Exception {
        mockMvc.perform(get("/api/reports/operational").header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isForbidden());
    }
}
