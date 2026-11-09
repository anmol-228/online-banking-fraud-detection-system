package com.sepro.obfds.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the application in a configuration that is unsafe to expose.
 *
 * <p>The repository is public, and so are a default signing secret, five demo passwords, a
 * localhost CORS origin and an "open registration" default. Each of them is harmless on a laptop
 * and a complete takeover on the internet. This guard turns "remember to override them" into
 * "cannot start without overriding them".</p>
 *
 * <p>It is a {@link BeanFactoryPostProcessor} so that it runs before any other bean exists: before
 * the datasource connects, before the seeder writes a user and before a port is opened. The
 * decision itself is a pure function ({@link #violations(Inputs)}) so every rule can be tested
 * without starting the application.</p>
 *
 * <p>Design rules:</p>
 * <ul>
 *   <li><b>Fail closed.</b> A bind address of {@code 0.0.0.0}, an unset address on a real web
 *       server, or a datasource host that is not loopback all count as "reachable from outside".
 *       Anything this class cannot parse also counts as outside.</li>
 *   <li><b>Isolated</b> means: not the {@code public} profile, bound to loopback only, and talking
 *       to a database on this machine. Only an isolated run may use published secrets, fixed demo
 *       passwords or open self-registration.</li>
 *   <li>The pool rule is not about security but about availability: see {@link #violations}.</li>
 * </ul>
 */
@Component
public class StartupSafetyGuard implements BeanFactoryPostProcessor, EnvironmentAware, ApplicationContextAware {

    static final String PREFIX = "OBFDS startup safety guard refused to start";

    /** Secrets that appear in the public repository (main configuration, tests, documentation). */
    static final Set<String> PUBLISHED_SECRETS = Set.of(
            "change-me-local-dev-secret-key-min-32-chars",
            "test-only-secret-key-for-automated-tests-32chars");

    /** The demo passwords documented in the repository. */
    static final Set<String> PUBLISHED_DEMO_PASSWORDS =
            Set.of("Admin@123", "Analyst@123", "Officer@123", "SysAdmin@123", "Customer@123");

    private static final Set<String> LOOPBACK_HOSTS =
            Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]");

    private Environment environment;
    private boolean webServerStarts;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        // Under MockMvc-style tests no port is opened, so an unset bind address means nothing.
        this.webServerStarts = applicationContext instanceof WebServerApplicationContext;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        List<String> problems = violations(Inputs.from(environment, webServerStarts));
        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder(PREFIX).append(":");
            problems.forEach(problem -> message.append("\n  - ").append(problem));
            throw new IllegalStateException(message.toString());
        }
    }

    /** Everything the rules look at, gathered once so the rules stay a pure function. */
    public record Inputs(
            Set<String> profiles,
            boolean webServerStarts,
            String bindAddress,
            String datasourceUrl,
            String jwtSecret,
            String jwtSecretFile,
            String seedMode,
            String adminPassword,
            String analystPassword,
            String officerPassword,
            String sysadminPassword,
            String customerPassword,
            String registrationMode,
            String inviteCode,
            String corsOrigins,
            String actuatorExposure,
            int tomcatThreads,
            int hikariPoolSize) {

        static Inputs from(Environment env, boolean webServerStarts) {
            Set<String> profiles = new LinkedHashSet<>();
            String[] active = env.getActiveProfiles();
            for (String profile : active.length > 0 ? active : env.getDefaultProfiles()) {
                profiles.add(profile.toLowerCase(Locale.ROOT));
            }
            return new Inputs(
                    profiles,
                    webServerStarts,
                    env.getProperty("server.address", ""),
                    env.getProperty("spring.datasource.url", ""),
                    env.getProperty("obfds.jwt.secret", ""),
                    env.getProperty("obfds.jwt.secret-file", ""),
                    env.getProperty("obfds.seed.mode", "demo-fixed"),
                    env.getProperty("obfds.seed.bootstrap.admin-password", ""),
                    env.getProperty("obfds.seed.bootstrap.analyst-password", ""),
                    env.getProperty("obfds.seed.bootstrap.officer-password", ""),
                    env.getProperty("obfds.seed.bootstrap.sysadmin-password", ""),
                    env.getProperty("obfds.seed.bootstrap.customer-password", ""),
                    env.getProperty("obfds.registration.mode", "closed"),
                    env.getProperty("obfds.registration.invite-code", ""),
                    env.getProperty("obfds.cors.allowed-origins", ""),
                    env.getProperty("management.endpoints.web.exposure.include", "health"),
                    env.getProperty("server.tomcat.threads.max", Integer.class, 200),
                    env.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class, 10));
        }
    }

    /**
     * Applies every rule and returns one human-readable sentence per violation (empty when safe).
     */
    public static List<String> violations(Inputs in) {
        List<String> problems = new ArrayList<>();

        boolean publicProfile = in.profiles().contains("public");
        boolean devOrTest = in.profiles().contains("dev") || in.profiles().contains("test");
        boolean loopbackBind = isLoopbackBind(in.bindAddress(), in.webServerStarts());
        boolean localDatabase = isLocalDatasource(in.datasourceUrl());
        boolean isolated = !publicProfile && loopbackBind && localDatabase;

        checkSecret(in, isolated, devOrTest, problems);
        checkSeed(in, isolated, problems);
        checkRegistration(in, isolated, problems);

        if (!isolated) {
            checkExposure(in, localDatabase, problems);
        }

        // Availability rule. An audit write runs in its own transaction (so a failure is still
        // recorded when the business transaction rolls back), which means one request can hold
        // two database connections at once. If more requests than half the pool run together, all
        // connections can be held by requests that each wait for a second one: nothing finishes
        // until the pool timeout, and every waiting request fails with HTTP 500.
        if (in.webServerStarts() && in.tomcatThreads() * 2 > in.hikariPoolSize()) {
            problems.add("Tomcat worker threads (" + in.tomcatThreads() + ") must not exceed half of the "
                    + "database connection pool (" + in.hikariPoolSize() + "): each request can hold two "
                    + "connections, so a larger thread count lets requests starve one another. Lower "
                    + "server.tomcat.threads.max or raise spring.datasource.hikari.maximum-pool-size.");
        }
        return problems;
    }

    private static void checkSecret(Inputs in, boolean isolated, boolean devOrTest, List<String> problems) {
        String secret = in.jwtSecret() == null ? "" : in.jwtSecret();
        boolean blank = secret.isBlank();
        boolean hasFile = in.jwtSecretFile() != null && !in.jwtSecretFile().isBlank();

        if (blank) {
            if (!hasFile) {
                problems.add("No JWT secret is configured. Set OBFDS_JWT_SECRET (at least 32 characters) "
                        + "or, for a local installation only, obfds.jwt.secret-file.");
            } else if (!isolated) {
                problems.add("A generated, file-persisted JWT secret is only allowed for an isolated local "
                        + "run. A deployment reachable from outside must supply OBFDS_JWT_SECRET from a "
                        + "secret store.");
            }
            return;
        }
        if (secret.length() < 32) {
            problems.add("The JWT secret is shorter than 32 characters.");
        }
        if (PUBLISHED_SECRETS.contains(secret) || secret.toLowerCase(Locale.ROOT).startsWith("change-me")) {
            if (!(devOrTest && isolated)) {
                problems.add("The JWT secret is a value published in the repository. Anyone can mint an "
                        + "administrator token with it. Only a loopback-only dev/test run may use it; "
                        + "set OBFDS_JWT_SECRET to a fresh random value.");
            }
        } else if (!isolated && secret.chars().distinct().count() < 12) {
            problems.add("The JWT secret has too little variety to be a random value.");
        }
    }

    private static void checkSeed(Inputs in, boolean isolated, List<String> problems) {
        String mode = in.seedMode() == null ? "" : in.seedMode().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "none" -> { }
            case "demo-fixed" -> {
                if (!isolated) {
                    problems.add("Seed mode demo-fixed creates users with the passwords published in the "
                            + "repository, so it is only allowed for an isolated local run. Use "
                            + "obfds.seed.mode=bootstrap-env or none.");
                }
            }
            case "bootstrap-env" -> {
                String[] passwords = {
                    in.adminPassword(), in.analystPassword(), in.officerPassword(),
                    in.sysadminPassword(), in.customerPassword()};
                String[] names = {"admin", "analyst", "officer", "sysadmin", "customer"};
                for (int i = 0; i < passwords.length; i++) {
                    String password = passwords[i] == null ? "" : passwords[i];
                    if (password.length() < 12) {
                        problems.add("Seed mode bootstrap-env needs a " + names[i]
                                + " password of at least 12 characters (obfds.seed.bootstrap." + names[i]
                                + "-password).");
                    } else if (PUBLISHED_DEMO_PASSWORDS.contains(password)) {
                        problems.add("The seed bootstrap " + names[i] + " password is one of the demo "
                                + "passwords published in the repository.");
                    }
                }
            }
            default -> problems.add("Unknown seed mode '" + in.seedMode()
                    + "'; use none, bootstrap-env or demo-fixed.");
        }
    }

    private static void checkRegistration(Inputs in, boolean isolated, List<String> problems) {
        String mode = in.registrationMode() == null ? "" : in.registrationMode().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "closed" -> { }
            case "open" -> {
                if (!isolated) {
                    problems.add("Open self-registration gives every visitor a funded account, so it is "
                            + "only allowed for an isolated local run. Use obfds.registration.mode=closed "
                            + "or invite.");
                }
            }
            case "invite" -> {
                if (in.inviteCode() == null || in.inviteCode().length() < 16) {
                    problems.add("Registration mode invite needs an invite code of at least 16 characters.");
                }
            }
            default -> problems.add("Unknown registration mode '" + in.registrationMode()
                    + "'; use closed, invite or open.");
        }
    }

    private static void checkExposure(Inputs in, boolean localDatabase, List<String> problems) {
        String origins = in.corsOrigins() == null ? "" : in.corsOrigins().trim();
        if (origins.isEmpty()) {
            problems.add("No CORS origin is configured (OBFDS_CORS_ALLOWED_ORIGINS); a deployment reachable "
                    + "from outside must name exactly the front-end origin.");
        } else {
            for (String origin : origins.split(",")) {
                String value = origin.trim().toLowerCase(Locale.ROOT);
                if (value.equals("*") || value.contains("localhost") || value.contains("127.0.0.1")
                        || value.contains("[::1]")) {
                    problems.add("CORS origin '" + origin.trim() + "' is not allowed outside an isolated run "
                            + "(wildcard or localhost).");
                } else if (!value.startsWith("https://")) {
                    problems.add("CORS origin '" + origin.trim() + "' must use https:// outside an isolated run.");
                }
            }
        }

        String exposure = in.actuatorExposure() == null ? "" : in.actuatorExposure().trim();
        if (!exposure.equalsIgnoreCase("health")) {
            problems.add("Actuator exposure must be exactly 'health' outside an isolated run (found '"
                    + exposure + "').");
        }

        String url = in.datasourceUrl() == null ? "" : in.datasourceUrl();
        if (!localDatabase && url.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql:")) {
            String lower = url.toLowerCase(Locale.ROOT);
            if (!(lower.contains("sslmode=verify_ca") || lower.contains("sslmode=verify_identity"))) {
                problems.add("A database on another host must be reached over verified TLS "
                        + "(add sslMode=VERIFY_CA or VERIFY_IDENTITY to the JDBC URL).");
            }
        }
    }

    // ------------------------------------------------------------------
    // Reachability helpers (package-private for unit tests)
    // ------------------------------------------------------------------

    static boolean isLoopbackBind(String address, boolean webServerStarts) {
        if (address == null || address.isBlank()) {
            // No address means "all interfaces" - but only if a server is really going to start.
            return !webServerStarts;
        }
        return LOOPBACK_HOSTS.contains(address.trim().toLowerCase(Locale.ROOT));
    }

    /** True for an in-process/file H2 database or a MySQL/H2-tcp URL whose only host is loopback. */
    static boolean isLocalDatasource(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:h2:mem:") || lower.startsWith("jdbc:h2:file:") || lower.startsWith("jdbc:h2:~")) {
            return true;
        }
        String hostPart;
        if (lower.startsWith("jdbc:mysql://")) {
            hostPart = url.trim().substring("jdbc:mysql://".length());
        } else if (lower.startsWith("jdbc:h2:tcp://")) {
            hostPart = url.trim().substring("jdbc:h2:tcp://".length());
        } else {
            return false;
        }
        int end = hostPart.length();
        for (char delimiter : new char[] {'/', '?'}) {
            int index = hostPart.indexOf(delimiter);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        String authority = hostPart.substring(0, end);
        if (authority.isEmpty() || authority.contains(",") || authority.contains("(") || authority.contains("@")) {
            return false; // several hosts, address=(...) syntax or credentials: do not guess
        }
        try {
            String host = URI.create("//" + authority).getHost();
            return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
