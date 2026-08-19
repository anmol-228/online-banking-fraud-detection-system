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

    /** Whether the fictional demo dataset should be created at startup. */
    private boolean seedDemoData = true;

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
        /** HMAC-SHA256 signing secret. Must be at least 32 characters. */
        private String secret = "change-me-local-dev-secret-key-min-32-chars";

        private long expiryMinutes = 120;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
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

        /** Start hour (inclusive) of the unusual-hour window, in the server time zone. */
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
