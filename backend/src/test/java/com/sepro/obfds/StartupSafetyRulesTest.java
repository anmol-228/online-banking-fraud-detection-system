package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;

import com.sepro.obfds.config.StartupSafetyGuard;
import com.sepro.obfds.config.StartupSafetyGuard.Inputs;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guard's rules as a truth table. Starting the whole application for each combination would be
 * slow and could not cover bind addresses such as 0.0.0.0 without opening a real listener, so the
 * decision function is tested directly (the end-to-end refusal is in {@link StartupSafetyGuardTest}).
 */
class StartupSafetyRulesTest {

    private static final String STRONG = "Q7pL2-vN8x-Zr4T-mW9c-Hs3Y-bK6d-Fe1J-uA5g";
    private static final String DEFAULT = "change-me-local-dev-secret-key-min-32-chars";

    /** A configuration that is valid for a loopback-only local run. */
    private static Inputs local() {
        return new Inputs(Set.of("mysql", "local"), true, "127.0.0.1",
                "jdbc:mysql://localhost:3306/obfds?useSSL=false", "", "C:/x/jwt.secret",
                "demo-fixed", "", "", "", "", "", "open", "", "http://localhost:5173", "health", 4, 8);
    }

    /** A configuration that is valid for a public deployment. */
    private static Inputs publicDeployment() {
        return new Inputs(Set.of("mysql", "public"), true, "",
                "jdbc:mysql://db.example.invalid:3306/obfds?sslMode=VERIFY_CA", STRONG, "",
                "bootstrap-env",
                "Zk7-Rules-Admin-Pass-1", "Zk7-Rules-Analyst-Pass-1", "Zk7-Rules-Officer-Pass-1",
                "Zk7-Rules-SysAdmin-Pass-1", "Zk7-Rules-Customer-Pass-1",
                "closed", "", "https://app.example.invalid", "health", 4, 8);
    }

    private static Inputs with(Inputs base, java.util.function.UnaryOperator<Builder> change) {
        return change.apply(new Builder(base)).build();
    }

    @Test
    @DisplayName("The reference local and public configurations are accepted")
    void referenceConfigurationsAreSafe() {
        assertThat(StartupSafetyGuard.violations(local())).isEmpty();
        assertThat(StartupSafetyGuard.violations(publicDeployment())).isEmpty();
    }

    @Test
    @DisplayName("Binding to all interfaces makes a local configuration unsafe")
    void allInterfacesBindIsNotIsolated() {
        List<String> problems = StartupSafetyGuard.violations(with(local(), b -> b.bind("0.0.0.0")));
        assertThat(problems).anyMatch(p -> p.contains("demo-fixed")).anyMatch(p -> p.contains("self-registration"));
    }

    @Test
    @DisplayName("An unset bind address on a real web server counts as all interfaces")
    void unsetBindOnRealServerIsNotIsolated() {
        assertThat(StartupSafetyGuard.violations(with(local(), b -> b.bind(""))))
                .anyMatch(p -> p.contains("demo-fixed"));
    }

    @Test
    @DisplayName("An unset bind address does not matter when no web server starts (MockMvc tests)")
    void unsetBindWithoutServerIsIsolated() {
        assertThat(StartupSafetyGuard.violations(with(local(), b -> b.bind("").server(false)))).isEmpty();
    }

    @Test
    @DisplayName("A database on another host makes the run non-isolated even when bound to loopback")
    void remoteDatabaseIsNotIsolated() {
        Inputs remote = with(local(), b -> b.url("jdbc:mysql://db.example.invalid:3306/obfds?sslMode=VERIFY_CA"));
        assertThat(StartupSafetyGuard.violations(remote))
                .anyMatch(p -> p.contains("demo-fixed"))
                .anyMatch(p -> p.contains("file-persisted JWT secret"));
    }

    @Test
    @DisplayName("Unparseable or multi-host JDBC URLs are treated as remote (fail closed)")
    void unparseableUrlsAreRemote() {
        for (String url : List.of(
                "jdbc:mysql://a.example,b.example/db", "jdbc:mysql://address=(host=x)(port=1)/db",
                "jdbc:mysql://user@host/db", "jdbc:postgresql://localhost/db", "jdbc:mysql:///db")) {
            assertThat(StartupSafetyGuard.violations(with(local(), b -> b.url(url))))
                    .as(url).anyMatch(p -> p.contains("demo-fixed"));
        }
    }

    @Test
    @DisplayName("Loopback spellings are all recognised as local")
    void loopbackSpellings() {
        for (String url : List.of(
                "jdbc:mysql://localhost/db", "jdbc:mysql://127.0.0.1:3306/db", "jdbc:mysql://[::1]:3306/db",
                "jdbc:h2:mem:x", "jdbc:h2:file:./data/x")) {
            assertThat(StartupSafetyGuard.violations(with(local(), b -> b.url(url)))).as(url).isEmpty();
        }
    }

    @Test
    @DisplayName("The published secrets are refused unless it is a loopback dev/test run")
    void publishedSecrets() {
        Inputs devWithDefault = with(local(), b -> b.profiles("dev").secret(DEFAULT).file(""));
        assertThat(StartupSafetyGuard.violations(devWithDefault)).isEmpty();

        assertThat(StartupSafetyGuard.violations(with(local(), b -> b.profiles("mysql", "local").secret(DEFAULT))))
                .anyMatch(p -> p.contains("published in the repository"));
        assertThat(StartupSafetyGuard.violations(with(devWithDefault, b -> b.bind("0.0.0.0"))))
                .anyMatch(p -> p.contains("published in the repository"));
        assertThat(StartupSafetyGuard.violations(with(devWithDefault, b -> b.profiles("dev", "public"))))
                .anyMatch(p -> p.contains("published in the repository"));
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.secret("change-me-anything-else-long-enough-1"))))
                .anyMatch(p -> p.contains("published in the repository"));
    }

    @Test
    @DisplayName("A missing, short or low-variety secret is refused")
    void weakSecrets() {
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.secret(""))))
                .anyMatch(p -> p.contains("No JWT secret"));
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.secret("short"))))
                .anyMatch(p -> p.contains("shorter than 32"));
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.secret("a".repeat(40)))))
                .anyMatch(p -> p.contains("too little variety"));
    }

    @Test
    @DisplayName("A file-persisted secret is allowed locally but never on a public deployment")
    void fileSecretOnlyLocal() {
        assertThat(StartupSafetyGuard.violations(local())).isEmpty();
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.secret("").file("/tmp/x"))))
                .anyMatch(p -> p.contains("file-persisted JWT secret"));
    }

    @Test
    @DisplayName("bootstrap-env needs five distinct-purpose passwords that are long and not published")
    void bootstrapPasswords() {
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.admin("short"))))
                .anyMatch(p -> p.contains("admin password of at least 12"));
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.customer("Customer@123"))))
                .anyMatch(p -> p.contains("demo passwords"));
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.customer("Customer@123456"))))
                .isEmpty();
    }

    @Test
    @DisplayName("Registration and seed modes are validated")
    void modes() {
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.registration("open"))))
                .anyMatch(p -> p.contains("self-registration"));
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.registration("invite").invite("short"))))
                .anyMatch(p -> p.contains("invite code of at least 16"));
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.registration("invite").invite("Invite-Code-Long-Enough"))))
                .isEmpty();
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.registration("maybe"))))
                .anyMatch(p -> p.contains("Unknown registration mode"));
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.seed("whatever"))))
                .anyMatch(p -> p.contains("Unknown seed mode"));
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.seed("none")))).isEmpty();
    }

    @Test
    @DisplayName("CORS must be an explicit https origin outside an isolated run")
    void cors() {
        for (String origin : List.of("", "*", "http://app.example.invalid", "https://localhost:5173", "https://x,http://127.0.0.1")) {
            assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.cors(origin))))
                    .as(origin).isNotEmpty();
        }
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.cors("https://a.example,https://b.example"))))
                .isEmpty();
    }

    @Test
    @DisplayName("Only the health endpoint may be exposed outside an isolated run")
    void actuator() {
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.exposure("health,env"))))
                .anyMatch(p -> p.contains("Actuator"));
    }

    @Test
    @DisplayName("A remote MySQL database requires verified TLS")
    void tls() {
        for (String url : List.of(
                "jdbc:mysql://db.example.invalid:3306/obfds", "jdbc:mysql://db.example.invalid/obfds?sslMode=REQUIRED",
                "jdbc:mysql://db.example.invalid/obfds?useSSL=false")) {
            assertThat(StartupSafetyGuard.violations(with(publicDeployment(), b -> b.url(url))))
                    .as(url).anyMatch(p -> p.contains("verified TLS"));
        }
        assertThat(StartupSafetyGuard.violations(with(publicDeployment(),
                b -> b.url("jdbc:mysql://db.example.invalid/obfds?sslMode=VERIFY_IDENTITY")))).isEmpty();
    }

    @Test
    @DisplayName("Tomcat threads times two may not exceed the pool, only when a server starts")
    void poolRule() {
        assertThat(StartupSafetyGuard.violations(with(local(), b -> b.threads(5)))).anyMatch(p -> p.contains("Tomcat"));
        assertThat(StartupSafetyGuard.violations(with(local(), b -> b.threads(4)))).isEmpty();
        assertThat(StartupSafetyGuard.violations(with(local(), b -> b.threads(200).server(false)))).isEmpty();
    }

    /** Test-only copy-and-modify helper for the immutable {@link Inputs} record. */
    private static final class Builder {
        Set<String> profiles; boolean server; String bind; String url; String secret; String file; String seed;
        String admin; String analyst; String officer; String sysadmin; String customer;
        String registration; String invite; String cors; String exposure; int threads; int pool;

        Builder(Inputs i) {
            profiles = i.profiles(); server = i.webServerStarts(); bind = i.bindAddress(); url = i.datasourceUrl();
            secret = i.jwtSecret(); file = i.jwtSecretFile(); seed = i.seedMode(); admin = i.adminPassword();
            analyst = i.analystPassword(); officer = i.officerPassword(); sysadmin = i.sysadminPassword();
            customer = i.customerPassword(); registration = i.registrationMode(); invite = i.inviteCode();
            cors = i.corsOrigins(); exposure = i.actuatorExposure(); threads = i.tomcatThreads(); pool = i.hikariPoolSize();
        }
        Builder profiles(String... p) { profiles = Set.of(p); return this; }
        Builder server(boolean v) { server = v; return this; }
        Builder bind(String v) { bind = v; return this; }
        Builder url(String v) { url = v; return this; }
        Builder secret(String v) { secret = v; return this; }
        Builder file(String v) { file = v; return this; }
        Builder seed(String v) { seed = v; return this; }
        Builder admin(String v) { admin = v; return this; }
        Builder customer(String v) { customer = v; return this; }
        Builder registration(String v) { registration = v; return this; }
        Builder invite(String v) { invite = v; return this; }
        Builder cors(String v) { cors = v; return this; }
        Builder exposure(String v) { exposure = v; return this; }
        Builder threads(int v) { threads = v; return this; }
        Inputs build() {
            return new Inputs(profiles, server, bind, url, secret, file, seed, admin, analyst, officer, sysadmin,
                    customer, registration, invite, cors, exposure, threads, pool);
        }
    }
}
