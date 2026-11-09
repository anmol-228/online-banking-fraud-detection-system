package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sepro.obfds.repository.ApplicationUserRepository;
import com.sepro.obfds.repository.UserRoleRepository;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The published demo passwords (listed in the repository) must not be what protects a public
 * deployment. {@code bootstrap-env} seeds the same synthetic data with passwords taken from the
 * environment; {@code none} seeds roles only.
 *
 * <p>The passwords below are throw-away values that exist only inside this test.</p>
 */
class SeedModeApiTest {

    private static final String BOOTSTRAP_ADMIN = "Zk7-Bootstrap-Admin-Only-Here";
    private static final String BOOTSTRAP_CUSTOMER = "Zk7-Bootstrap-Customer-Only-Here";

    private static String loginBody(String username, String password) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(Map.of("username", username, "password", password));
    }

    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
        "obfds.seed.mode=bootstrap-env",
        "obfds.seed.bootstrap.admin-password=" + BOOTSTRAP_ADMIN,
        "obfds.seed.bootstrap.analyst-password=Zk7-Bootstrap-Analyst-Only-Here",
        "obfds.seed.bootstrap.officer-password=Zk7-Bootstrap-Officer-Only-Here",
        "obfds.seed.bootstrap.sysadmin-password=Zk7-Bootstrap-SysAdmin-Only-Here",
        "obfds.seed.bootstrap.customer-password=" + BOOTSTRAP_CUSTOMER,
        "spring.datasource.url=jdbc:h2:mem:seed-bootstrap;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
    })
    @Nested
    class BootstrapEnv extends AbstractApiTest {

        @Test
        @DisplayName("SEC-2 The published demo admin password does not work in bootstrap-env mode")
        void publishedPasswordsAreRejected() throws Exception {
            for (String[] published : new String[][] {
                {"admin.bank", "Admin@123"}, {"analyst.fraud", "Analyst@123"},
                {"ops.officer", "Officer@123"}, {"sys.admin", "SysAdmin@123"},
                {"ravi.kumar", "Customer@123"}, {"meera.nair", "Customer@123"}}) {
                mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(published[0], published[1])))
                        .andExpect(status().isUnauthorized());
            }
        }

        @Test
        @DisplayName("SEC-2 The environment-supplied passwords do work, and carry the right roles")
        void bootstrapPasswordsWork() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("admin.bank", BOOTSTRAP_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles[0]").value("BANK_ADMIN"));
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("ravi.kumar", BOOTSTRAP_CUSTOMER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
        }
    }

    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
        "obfds.seed.mode=none",
        "spring.datasource.url=jdbc:h2:mem:seed-none;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
    })
    @Nested
    class NoSeed extends AbstractApiTest {

        @Autowired private ApplicationUserRepository users;
        @Autowired private UserRoleRepository roles;

        @Test
        @DisplayName("SEC-2 Mode none creates the reference roles but no users at all")
        void noUsersAreCreated() {
            assertThat(users.count()).isZero();
            assertThat(roles.count()).isGreaterThanOrEqualTo(5);
        }
    }
}
