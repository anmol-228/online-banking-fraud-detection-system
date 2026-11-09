package com.sepro.obfds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sepro.obfds.entity.ApplicationUser;
import com.sepro.obfds.entity.RoleName;
import com.sepro.obfds.entity.UserRole;
import com.sepro.obfds.repository.ApplicationUserRepository;
import com.sepro.obfds.repository.UserRoleRepository;
import com.sepro.obfds.security.JwtService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * A token must never be trusted for more than the database says about its subject right now.
 *
 * <p>Before the per-request lookup existed, the filter believed the {@code roles} claim inside the
 * token and never asked whether the user still existed or was still enabled. Anyone holding the
 * signing secret could therefore mint an administrator token for any subject, and a disabled or
 * demoted user kept their old privileges until the token expired (security finding SEC-1).</p>
 */
class AuthorizationFreshnessApiTest extends AbstractApiTest {

    @Autowired private JwtService jwtService;
    @Autowired private ApplicationUserRepository users;
    @Autowired private UserRoleRepository roles;

    @Test
    @DisplayName("SEC-1 A token whose claims say BANK_ADMIN does not elevate a customer")
    void roleClaimInsideTheTokenIsNotTrusted() throws Exception {
        String forged = "Bearer " + jwtService.issueToken(CUSTOMER_USERNAME, List.of("ROLE_BANK_ADMIN"));

        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, forged))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-1 A token for a subject that does not exist is rejected as unauthenticated")
    void tokenForUnknownSubjectIsRejected() throws Exception {
        String forged = "Bearer " + jwtService.issueToken("ghost.user", List.of("ROLE_BANK_ADMIN"));

        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEC-1 A token stops working the moment its user is disabled")
    void disabledUsersOldTokenIsRejected() throws Exception {
        String token = login(SECOND_CUSTOMER_USERNAME, SECOND_CUSTOMER_PASSWORD);
        mockMvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());

        ApplicationUser meera = users.findByUsername(SECOND_CUSTOMER_USERNAME).orElseThrow();
        meera.setEnabled(false);
        users.saveAndFlush(meera);

        mockMvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEC-1 A removed role takes effect on the very next request")
    void roleRemovedFromUserTakesEffectImmediately() throws Exception {
        String adminToken = adminToken();
        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());

        ApplicationUser admin = users.findByUsername(ADMIN_USERNAME).orElseThrow();
        UserRole customer = roles.findByName(RoleName.CUSTOMER).orElseThrow();
        admin.setRoles(new LinkedHashSet<>(Set.of(customer)));
        users.saveAndFlush(admin);

        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-1 A disabled user and a wrong password look identical to the caller")
    void disabledUserLoginIsIndistinguishableFromWrongPassword() throws Exception {
        ApplicationUser meera = users.findByUsername(SECOND_CUSTOMER_USERNAME).orElseThrow();
        meera.setEnabled(false);
        users.saveAndFlush(meera);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", SECOND_CUSTOMER_USERNAME, "password", SECOND_CUSTOMER_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("SEC-1 Regression guard: a genuine administrator token still works")
    void genuineAdministratorTokenStillWorks() throws Exception {
        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk());
    }
}
