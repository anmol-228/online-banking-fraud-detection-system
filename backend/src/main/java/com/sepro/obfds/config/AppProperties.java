package com.sepro.obfds.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised application settings.
 *
 * <p>Keeping the JWT secret, the CORS origins and every fraud threshold in configuration rather
 * than in code means the behaviour of the fraud engine can be tuned for a demonstration without
 * recompiling the application (NFR-07).</p>
 */
@ConfigurationProperties(prefix = "obfds")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Fraud fraud = new Fraud();
    private final Verification verification = new Verification();
    private final Seed seed = new Seed();
    private final Registration registration = new Registration();
    private final Security security = new Security();
    private final Review review = new Review();
    private final Escalation escalation = new Escalation();

    /** Whether the fictional demo dataset should be created at startup. */
    private boolean seedDemoData = true;

    public Seed getSeed() {
        return seed;
    }

    public Registration getRegistration() {
        return registration;
    }

    public Security getSecurity() {
        return security;
    }

    public Review getReview() {
        return review;
    }

    public Escalation getEscalation() {
        return escalation;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Cors getCors() {
        return cors;
    }

    public Fraud getFraud() {
        return fraud;
    }

    public Verification getVerification() {
        return verification;
    }

    public boolean isSeedDemoData() {
        return seedDemoData;
    }

    public void setSeedDemoData(boolean seedDemoData) {
        this.seedDemoData = seedDemoData;
    }

    public static class Jwt {
        /**
         * HMAC-SHA256 signing secret, at least 32 characters. There is deliberately no default:
         * the value published in the repository is only configured for the {@code dev} profile,
         * and the {@code StartupSafetyGuard} refuses it everywhere else.
         */
        private String secret = "";

        /**
         * Optional file holding a per-installation secret. When {@link #secret} is blank and this
         * is set, the file is read, or created with a random value on first start, so a local
         * installation keeps its sessions across restarts without a secret ever being committed.
         */
        private String secretFile = "";

        private long expiryMinutes = 120;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getSecretFile() {
            return secretFile;
        }

        public void setSecretFile(String secretFile) {
            this.secretFile = secretFile;
        }

        public long getExpiryMinutes() {
            return expiryMinutes;
        }

        public void setExpiryMinutes(long expiryMinutes) {
            this.expiryMinutes = expiryMinutes;
        }
    }

    public static class Cors {
        private String allowedOrigins = "http://localhost:5173";

        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    /**
     * Thresholds used by the deterministic fraud rule engine (FR-11, FR-12).
     *
     * <p>These figures are chosen so that the workflow is easy to demonstrate.
     * They do not represent the thresholds used by any real bank.</p>
     */
    public static class Fraud {
        /** Amount above which a transfer is treated as unusually large. */
        private BigDecimal highAmount = new BigDecimal("50000");

        /** Amount above which a transfer is treated as very large. */
        private BigDecimal veryHighAmount = new BigDecimal("100000");

        /** A beneficiary added within this many hours counts as newly added. */
        private int newBeneficiaryHours = 24;

        /** Number of transfers within the velocity window that counts as rapid activity. */
        private int velocityCount = 3;

        /** Length of the velocity window in minutes. */
        private int velocityMinutes = 10;

        /** Fraction of the balance above which a transfer is treated as draining the account. */
        private BigDecimal balanceFraction = new BigDecimal("0.80");

        /**
         * The time zone in which the unusual-hour window is read. Fixed to a named zone (not the
         * machine's default) so a laptop, a cloud host set to UTC and the browser demo all agree
         * that 02:00 is 02:00. Stored timestamps stay UTC; this only decides which clock hour a
         * transfer falls into for the rule.
         */
        private String zone = "Asia/Kolkata";

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        /** Start hour (inclusive) of the unusual-hour window, in {@link #zone}. */
        private int oddHourStart = 0;

        /** End hour (exclusive) of the unusual-hour window. */
        private int oddHourEnd = 5;

        /** Score at or above which a transfer is classified MEDIUM. */
        private int mediumScoreThreshold = 30;

        /** Score at or above which a transfer is classified HIGH. */
        private int highScoreThreshold = 60;

        public BigDecimal getHighAmount() {
            return highAmount;
        }

        public void setHighAmount(BigDecimal highAmount) {
            this.highAmount = highAmount;
        }

        public BigDecimal getVeryHighAmount() {
            return veryHighAmount;
        }

        public void setVeryHighAmount(BigDecimal veryHighAmount) {
            this.veryHighAmount = veryHighAmount;
        }

        public int getNewBeneficiaryHours() {
            return newBeneficiaryHours;
        }

        public void setNewBeneficiaryHours(int newBeneficiaryHours) {
            this.newBeneficiaryHours = newBeneficiaryHours;
        }

        public int getVelocityCount() {
            return velocityCount;
        }

        public void setVelocityCount(int velocityCount) {
            this.velocityCount = velocityCount;
        }

        public int getVelocityMinutes() {
            return velocityMinutes;
        }

        public void setVelocityMinutes(int velocityMinutes) {
            this.velocityMinutes = velocityMinutes;
        }

        public BigDecimal getBalanceFraction() {
            return balanceFraction;
        }

        public void setBalanceFraction(BigDecimal balanceFraction) {
            this.balanceFraction = balanceFraction;
        }

        public int getOddHourStart() {
            return oddHourStart;
        }

        public void setOddHourStart(int oddHourStart) {
            this.oddHourStart = oddHourStart;
        }

        public int getOddHourEnd() {
            return oddHourEnd;
        }

        public void setOddHourEnd(int oddHourEnd) {
            this.oddHourEnd = oddHourEnd;
        }

        public int getMediumScoreThreshold() {
            return mediumScoreThreshold;
        }

        public void setMediumScoreThreshold(int mediumScoreThreshold) {
            this.mediumScoreThreshold = mediumScoreThreshold;
        }

        public int getHighScoreThreshold() {
            return highScoreThreshold;
        }

        public void setHighScoreThreshold(int highScoreThreshold) {
            this.highScoreThreshold = highScoreThreshold;
        }
    }

    /** How the initial users are created (see {@link DataSeeder}). */
    public static class Seed {
        /** {@code none}, {@code bootstrap-env} or {@code demo-fixed}. */
        private String mode = "demo-fixed";
        private final Bootstrap bootstrap = new Bootstrap();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Bootstrap getBootstrap() {
            return bootstrap;
        }

        /** Passwords for {@code bootstrap-env} mode; they come from the environment, never from code. */
        public static class Bootstrap {
            private String adminPassword = "";
            private String analystPassword = "";
            private String officerPassword = "";
            private String sysadminPassword = "";
            private String customerPassword = "";

            public String getAdminPassword() {
                return adminPassword;
            }

            public void setAdminPassword(String adminPassword) {
                this.adminPassword = adminPassword;
            }

            public String getAnalystPassword() {
                return analystPassword;
            }

            public void setAnalystPassword(String analystPassword) {
                this.analystPassword = analystPassword;
            }

            public String getOfficerPassword() {
                return officerPassword;
            }

            public void setOfficerPassword(String officerPassword) {
                this.officerPassword = officerPassword;
            }

            public String getSysadminPassword() {
                return sysadminPassword;
            }

            public void setSysadminPassword(String sysadminPassword) {
                this.sysadminPassword = sysadminPassword;
            }

            public String getCustomerPassword() {
                return customerPassword;
            }

            public void setCustomerPassword(String customerPassword) {
                this.customerPassword = customerPassword;
            }
        }
    }

    /** Who may create a customer profile through the public registration endpoint. */
    public static class Registration {
        /** {@code open}, {@code invite} or {@code closed}. */
        private String mode = "closed";

        /** Shared invitation code for {@code invite} mode. */
        private String inviteCode = "";

        /** Upper bound on the number of customers; 0 means unlimited. */
        private long maxCustomers = 0;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getInviteCode() {
            return inviteCode;
        }

        public void setInviteCode(String inviteCode) {
            this.inviteCode = inviteCode;
        }

        public long getMaxCustomers() {
            return maxCustomers;
        }

        public void setMaxCustomers(long maxCustomers) {
            this.maxCustomers = maxCustomers;
        }
    }

    /** Abuse controls for the login endpoint (SEC-4). */
    public static class Security {
        /** Consecutive failures for one (username, client address) before it is locked. */
        private int loginMaxFailures = 5;

        /** Initial lockout; each further lockout doubles it, up to eight times this value. */
        private long loginLockoutSeconds = 30;

        /** Attempts of any kind from one client address per minute before it is slowed down. */
        private int loginMaxPerMinutePerAddress = 60;

        /** Bound on the number of tracked keys, so the tracker cannot be used to exhaust memory. */
        private int throttleMaxEntries = 10_000;

        public int getLoginMaxFailures() {
            return loginMaxFailures;
        }

        public void setLoginMaxFailures(int loginMaxFailures) {
            this.loginMaxFailures = loginMaxFailures;
        }

        public long getLoginLockoutSeconds() {
            return loginLockoutSeconds;
        }

        public void setLoginLockoutSeconds(long loginLockoutSeconds) {
            this.loginLockoutSeconds = loginLockoutSeconds;
        }

        public int getLoginMaxPerMinutePerAddress() {
            return loginMaxPerMinutePerAddress;
        }

        public void setLoginMaxPerMinutePerAddress(int loginMaxPerMinutePerAddress) {
            this.loginMaxPerMinutePerAddress = loginMaxPerMinutePerAddress;
        }

        public int getThrottleMaxEntries() {
            return throttleMaxEntries;
        }

        public void setThrottleMaxEntries(int throttleMaxEntries) {
            this.throttleMaxEntries = throttleMaxEntries;
        }
    }

    /** How long a held high-risk transfer is expected to wait for an analyst. */
    public static class Review {
        /** The review TARGET in hours: shown to people, never enforced and never releases money. */
        private int targetHours = 24;

        public int getTargetHours() {
            return targetHours;
        }

        public void setTargetHours(int targetHours) {
            this.targetHours = targetHours;
        }
    }

    /**
     * Human-operated escalation handoff. The system sends nothing: it prepares a mail draft
     * addressed to one of these pre-approved mailboxes, and a person decides whether to send it.
     */
    public static class Escalation {
        /** Key (what the UI sends) to mailbox (what the draft is addressed to). Empty = handoff off. */
        private java.util.Map<String, String> recipients = new java.util.LinkedHashMap<>();

        /** Handoffs one staff user may prepare in 24 hours. */
        private int maxPerDay = 10;

        public java.util.Map<String, String> getRecipients() {
            return recipients;
        }

        public void setRecipients(java.util.Map<String, String> recipients) {
            this.recipients = recipients;
        }

        public int getMaxPerDay() {
            return maxPerDay;
        }

        public void setMaxPerDay(int maxPerDay) {
            this.maxPerDay = maxPerDay;
        }
    }

    /** Settings for the additional verification challenge (FR-14). */
    public static class Verification {
        private int codeValidityMinutes = 10;
        private int maxAttempts = 3;

        public int getCodeValidityMinutes() {
            return codeValidityMinutes;
        }

        public void setCodeValidityMinutes(int codeValidityMinutes) {
            this.codeValidityMinutes = codeValidityMinutes;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }
}
