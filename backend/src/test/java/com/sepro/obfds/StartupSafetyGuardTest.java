package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts the real application (real servlet container, bound to loopback on an ephemeral port)
 * and checks that an unsafe configuration cannot come up.
 *
 * <p>The refusals matter because the repository publishes a default signing secret, five demo
 * passwords and a localhost CORS origin. Any one of them reaching a public deployment is a
 * complete takeover (SEC-1, SEC-2, SEC-3). The guard therefore fails closed: every profile except
 * a loopback-only {@code dev}/{@code test} run refuses the published secret.</p>
 *
 * <p>Every context uses its own in-memory database, so these tests cannot interfere with the
 * shared context used by the other integration tests.</p>
 */
class StartupSafetyGuardTest {

    private static final String PUBLISHED_DEFAULT_SECRET = "change-me-local-dev-secret-key-min-32-chars";
    private static final String PUBLISHED_TEST_SECRET = "test-only-secret-key-for-automated-tests-32chars";
    private static final String STRONG_SECRET = "Q7pL2-vN8x-Zr4T-mW9c-Hs3Y-bK6d-Fe1J-uA5g";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** A configuration that satisfies every rule of the {@code public} profile. */
    private static Map<String, String> validPublic() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("spring.profiles.active", "test,public");
        props.put("obfds.jwt.secret", STRONG_SECRET);
        props.put("obfds.seed.mode", "bootstrap-env");
        props.put("obfds.seed.bootstrap.admin-password", "Zk7-Guard-Admin-Only-Here-1");
        props.put("obfds.seed.bootstrap.analyst-password", "Zk7-Guard-Analyst-Only-Here-1");
        props.put("obfds.seed.bootstrap.officer-password", "Zk7-Guard-Officer-Only-Here-1");
        props.put("obfds.seed.bootstrap.sysadmin-password", "Zk7-Guard-SysAdmin-Only-Here-1");
        props.put("obfds.seed.bootstrap.customer-password", "Zk7-Guard-Customer-Only-Here-1");
        props.put("obfds.registration.mode", "closed");
        props.put("obfds.cors.allowed-origins", "https://app.example.invalid");
        return props;
    }

    private static ConfigurableApplicationContext start(Map<String, String> props) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.main.banner-mode=off",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "--spring.jmx.enabled=false",
                "--spring.datasource.url=jdbc:h2:mem:guard-" + SEQUENCE.incrementAndGet()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"));
        props.forEach((key, value) -> args.add("--" + key + "=" + value));
        return new SpringApplicationBuilder(ObfdsApplication.class).run(args.toArray(String[]::new));
    }

    private static void startAndClose(Map<String, String> props) {
        try (ConfigurableApplicationContext context = start(props)) {
            // The context is only needed to prove that it started.
            context.getId();
        }
    }

    private static void assertRefused(Map<String, String> props, String keyword) {
        assertThatThrownBy(() -> startAndClose(props))
                .as("an unsafe configuration must not start (%s)", keyword)
                .hasStackTraceContaining("startup safety")
                .hasStackTraceContaining(keyword);
    }

    @Test
    @DisplayName("SEC-1 Positive control: a fully valid public configuration starts")
    void validPublicConfigurationStarts() {
        assertThatCode(() -> startAndClose(validPublic())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SEC-1 The published default signing secret is refused on the public profile")
    void publishedDefaultSecretIsRefusedOnPublic() {
        Map<String, String> props = validPublic();
        props.put("obfds.jwt.secret", PUBLISHED_DEFAULT_SECRET);
        assertRefused(props, "JWT secret");
    }

    @Test
    @DisplayName("SEC-1 The published test secret is also refused on the public profile")
    void publishedTestSecretIsRefusedOnPublic() {
        Map<String, String> props = validPublic();
        props.put("obfds.jwt.secret", PUBLISHED_TEST_SECRET);
        assertRefused(props, "JWT secret");
    }

    @Test
    @DisplayName("SEC-1 Any profile other than dev/test refuses the published default secret")
    void publishedDefaultSecretIsRefusedOnAnyOtherProfile() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("spring.profiles.active", "staging");
        props.put("spring.jpa.hibernate.ddl-auto", "create-drop");
        props.put("obfds.jwt.secret", PUBLISHED_DEFAULT_SECRET);
        assertRefused(props, "JWT secret");
    }

    @Test
    @DisplayName("SEC-1 Regression guard: the dev/test profile on loopback may still use a published secret")
    void testProfileOnLoopbackMayUseThePublishedSecret() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("spring.profiles.active", "test");
        props.put("obfds.jwt.secret", PUBLISHED_DEFAULT_SECRET);
        assertThatCode(() -> startAndClose(props)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SEC-2 Fixed demo passwords (demo-fixed seeding) are refused on the public profile")
    void demoFixedSeedingIsRefusedOnPublic() {
        Map<String, String> props = validPublic();
        props.put("obfds.seed.mode", "demo-fixed");
        assertRefused(props, "seed");
    }

    @Test
    @DisplayName("SEC-2 bootstrap-env without its passwords is refused")
    void bootstrapEnvWithoutPasswordsIsRefused() {
        Map<String, String> props = validPublic();
        props.remove("obfds.seed.bootstrap.admin-password");
        assertRefused(props, "seed");
    }

    @Test
    @DisplayName("SEC-2 bootstrap-env with a published demo password is refused")
    void bootstrapEnvWithPublishedPasswordIsRefused() {
        Map<String, String> props = validPublic();
        props.put("obfds.seed.bootstrap.admin-password", "Admin@123");
        assertRefused(props, "seed");
    }

    @Test
    @DisplayName("SEC-3 Open self-registration is refused on the public profile")
    void openRegistrationIsRefusedOnPublic() {
        Map<String, String> props = validPublic();
        props.put("obfds.registration.mode", "open");
        assertRefused(props, "registration");
    }

    @Test
    @DisplayName("SEC-3 A localhost CORS origin is refused on the public profile")
    void localhostCorsOriginIsRefusedOnPublic() {
        Map<String, String> props = validPublic();
        props.put("obfds.cors.allowed-origins", "http://localhost:5173");
        assertRefused(props, "CORS");
    }

    @Test
    @DisplayName("PERF-1 More Tomcat threads than half the connection pool is refused (pool starvation)")
    void tomcatThreadsAboveHalfThePoolAreRefused() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("spring.profiles.active", "test");
        props.put("server.tomcat.threads.max", "50");
        props.put("spring.datasource.hikari.maximum-pool-size", "10");
        assertRefused(props, "Tomcat");
    }
}
