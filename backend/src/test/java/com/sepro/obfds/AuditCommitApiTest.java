package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sepro.obfds.audit.AuditAction;
import com.sepro.obfds.entity.AuditOutcome;
import com.sepro.obfds.repository.AuditLogRepository;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression test for the durability of the audit trail (FR-20, NFR-10).
 *
 * <p>This class deliberately does <strong>not</strong> extend {@link AbstractApiTest}, because it
 * must not run inside a test-managed transaction. A failed login is audited on a code path that
 * then throws, so the audit write has to be committed in its own transaction. Wrapped in a test
 * transaction the row would be visible to the assertions either way, and the defect this test
 * exists to catch would go unnoticed.</p>
 *
 * <p>The test only ever produces audit rows, so it cannot disturb the data other tests rely
 * on.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditCommitApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("TC-15 A failed login is committed to the audit trail even though the login was rejected")
    void failedLoginSurvivesTheRejection() throws Exception {
        long before = auditLogRepository.countByAction(AuditAction.LOGIN_FAILURE);

        String body = objectMapper.writeValueAsString(
                Map.of("username", "ravi.kumar", "password", "DefinitelyWrong@123"));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        long after = auditLogRepository.countByAction(AuditAction.LOGIN_FAILURE);
        assertThat(after)
                .as("the failed login must be recorded in its own committed transaction")
                .isEqualTo(before + 1);

        assertThat(auditLogRepository.findAll())
                .filteredOn(entry -> AuditAction.LOGIN_FAILURE.equals(entry.getAction()))
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.FAILURE));
    }
}
