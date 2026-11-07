package com.sepro.obfds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Public registration hands out a funded account, so on a public deployment it has to be closed
 * (or gated by an invitation) rather than open to anyone who finds the URL.
 */
class RegistrationGateApiTest {

    private static Map<String, String> newCustomer(String suffix) {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("username", "gate.user." + suffix);
        request.put("password", "GateUser@12345");
        request.put("fullName", "Gate Test " + suffix);
        request.put("email", "gate.user." + suffix + "@demomail.example");
        return request;
    }

    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
        "obfds.registration.mode=closed",
        "spring.datasource.url=jdbc:h2:mem:reg-closed;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
    })
    @Nested
    class Closed extends AbstractApiTest {

        @Test
        @DisplayName("SEC-3 Closed registration refuses everyone and creates nothing")
        void closedRegistrationRefuses() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(newCustomer("closed"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("REGISTRATION_CLOSED"));
        }
    }

    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
        "obfds.registration.mode=invite",
        "obfds.registration.invite-code=Invite-Only-Test-Code-1",
        "spring.datasource.url=jdbc:h2:mem:reg-invite;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
    })
    @Nested
    class InviteOnly extends AbstractApiTest {

        @Test
        @DisplayName("SEC-3 Invite mode refuses a request with no invite code")
        void missingInviteIsRefused() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(newCustomer("noinvite"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("REGISTRATION_CLOSED"));
        }

        @Test
        @DisplayName("SEC-3 Invite mode refuses a wrong invite code")
        void wrongInviteIsRefused() throws Exception {
            Map<String, String> request = newCustomer("wronginvite");
            request.put("inviteCode", "not-the-code");
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("REGISTRATION_CLOSED"));
        }

        @Test
        @DisplayName("SEC-3 Invite mode accepts the correct invite code")
        void correctInviteIsAccepted() throws Exception {
            Map<String, String> request = newCustomer("goodinvite");
            request.put("inviteCode", "Invite-Only-Test-Code-1");
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(request)))
                    .andExpect(status().isCreated());
        }
    }
}
