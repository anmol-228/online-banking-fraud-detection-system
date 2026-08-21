package com.sepro.obfds.fraud;

import com.sepro.obfds.config.AppProperties;
import com.sepro.obfds.entity.Account;
import com.sepro.obfds.entity.Beneficiary;
import com.sepro.obfds.entity.RiskLevel;
import com.sepro.obfds.entity.VerificationStatus;
import com.sepro.obfds.repository.TransactionRepository;
import com.sepro.obfds.repository.VerificationRequestRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The fraud detection module (FR-11, FR-12).
 *
 * <p>This is a deterministic, rule-based scoring engine. Each rule that fires adds a fixed number
 * of points, the points are added up, and the total is mapped to a risk band. Running the same
 * transfer against the same history always produces the same score, which is what makes the
 * behaviour testable and straightforward to explain.</p>
 *
 * <p><strong>Fraud monitoring is intentionally simplified for demonstration and is not a
 * production fraud-detection model.</strong> It does not use machine learning, it does not learn
 * from past decisions, and the thresholds are chosen so that the workflow is easy to demonstrate
 * rather than to reflect the behaviour of any real bank.</p>
 *
 * <p>Score bands: LOW 0-29, MEDIUM 30-59, HIGH 60 and above. All thresholds come from
 * configuration so they can be tuned without changing code (NFR-07).</p>
 *
 * <p><strong>Eight scored rules, evaluated by six checks.</strong> The documentation counts the
 * rules the way the scoring model does — one rule per risk factor that can be raised, each with
 * its own code and its own point value, which is what {@code POINTS_*} below enumerates. Two of
 * the six checks choose between a pair of mutually exclusive rules rather than raising one
 * unconditionally: the amount check raises either VERY_HIGH_AMOUNT or HIGH_AMOUNT, and the
 * beneficiary check raises either UNREGISTERED_PAYEE or NEW_BENEFICIARY. Six methods, eight
 * rules, and a maximum reachable score of 140.</p>
 */
@Service
public class FraudRiskService {

    // Points contributed by each rule. Kept together so the whole scoring model is visible at once.
    private static final int POINTS_VERY_HIGH_AMOUNT = 50;
    private static final int POINTS_HIGH_AMOUNT = 35;
    private static final int POINTS_NEW_BENEFICIARY = 25;
    private static final int POINTS_UNREGISTERED_PAYEE = 20;
    private static final int POINTS_VELOCITY = 20;
    private static final int POINTS_REPEATED_FAILED_VERIFICATION = 20;
    private static final int POINTS_BALANCE_DRAIN = 15;
    private static final int POINTS_UNUSUAL_HOUR = 10;

    private final AppProperties.Fraud config;
    private final TransactionRepository transactionRepository;
    private final VerificationRequestRepository verificationRequestRepository;
    private final ZoneId zoneId = ZoneId.systemDefault();

    public FraudRiskService(
            AppProperties properties,
            TransactionRepository transactionRepository,
            VerificationRequestRepository verificationRequestRepository) {
        this.config = properties.getFraud();
        this.transactionRepository = transactionRepository;
        this.verificationRequestRepository = verificationRequestRepository;
    }

    /**
     * Assesses one proposed transfer.
     *
     * @param sourceAccount the account the money would leave
     * @param beneficiary the saved payee, or null when the customer typed an account number that
     *     is not in their payee list
     * @param amount the transfer amount
     * @param now the moment of assessment, passed in rather than read from the clock so that
     *     tests can assess a transfer at a chosen time
     */
    @Transactional(readOnly = true)
    public RiskEvaluation evaluate(
            Account sourceAccount, Beneficiary beneficiary, BigDecimal amount, Instant now) {

        List<RiskFactor> factors = new ArrayList<>();

        addAmountFactor(factors, amount);
        addBeneficiaryFactor(factors, beneficiary, now);
        addVelocityFactor(factors, sourceAccount, now);
        addBalanceDrainFactor(factors, sourceAccount, amount);
        addUnusualHourFactor(factors, now);
        addRepeatedFailedVerificationFactor(factors, sourceAccount, now);

        int score = factors.stream().mapToInt(RiskFactor::points).sum();
        return new RiskEvaluation(score, classify(score), List.copyOf(factors));
    }

    /** Maps a score to a band. Public so tests and documentation can use the same mapping. */
    public RiskLevel classify(int score) {
        if (score >= config.getHighScoreThreshold()) {
            return RiskLevel.HIGH;
        }
        if (score >= config.getMediumScoreThreshold()) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    /** Amount check — raises VERY_HIGH_AMOUNT or HIGH_AMOUNT, never both. */
    private void addAmountFactor(List<RiskFactor> factors, BigDecimal amount) {
        if (amount.compareTo(config.getVeryHighAmount()) >= 0) {
            factors.add(new RiskFactor(
                    "VERY_HIGH_AMOUNT",
                    "Transfer amount is at or above the very high limit of " + config.getVeryHighAmount(),
                    POINTS_VERY_HIGH_AMOUNT));
        } else if (amount.compareTo(config.getHighAmount()) >= 0) {
            factors.add(new RiskFactor(
                    "HIGH_AMOUNT",
                    "Transfer amount is at or above the high limit of " + config.getHighAmount(),
                    POINTS_HIGH_AMOUNT));
        }
    }

    /**
     * Beneficiary check — raises UNREGISTERED_PAYEE when the payee was never saved, otherwise
     * NEW_BENEFICIARY when it was saved very recently.
     */
    private void addBeneficiaryFactor(List<RiskFactor> factors, Beneficiary beneficiary, Instant now) {
        if (beneficiary == null) {
            factors.add(new RiskFactor(
                    "UNREGISTERED_PAYEE",
                    "Destination account is not in the saved beneficiary list of the customer",
                    POINTS_UNREGISTERED_PAYEE));
            return;
        }
        Instant cutoff = now.minus(config.getNewBeneficiaryHours(), ChronoUnit.HOURS);
        if (beneficiary.getCreatedAt() != null && beneficiary.getCreatedAt().isAfter(cutoff)) {
            factors.add(new RiskFactor(
                    "NEW_BENEFICIARY",
                    "Beneficiary was added within the last " + config.getNewBeneficiaryHours() + " hours",
                    POINTS_NEW_BENEFICIARY));
        }
    }

    /** Velocity check — raises RAPID_TRANSFERS after several transfers in a short period. */
    private void addVelocityFactor(List<RiskFactor> factors, Account sourceAccount, Instant now) {
        Long customerId = sourceAccount.getCustomer().getId();
        Instant windowStart = now.minus(Duration.ofMinutes(config.getVelocityMinutes()));
        long recentCount =
                transactionRepository.countBySourceAccountCustomerIdAndCreatedAtAfter(customerId, windowStart);

        if (recentCount >= config.getVelocityCount()) {
            factors.add(new RiskFactor(
                    "RAPID_TRANSFERS",
                    "Customer has made " + recentCount + " transfers in the last "
                            + config.getVelocityMinutes() + " minutes",
                    POINTS_VELOCITY));
        }
    }

    /** Balance check — raises BALANCE_DRAIN when the transfer would take most of the balance. */
    private void addBalanceDrainFactor(
            List<RiskFactor> factors, Account sourceAccount, BigDecimal amount) {

        BigDecimal balance = sourceAccount.getBalance();
        if (balance.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal threshold = balance.multiply(config.getBalanceFraction());
        if (amount.compareTo(threshold) > 0) {
            factors.add(new RiskFactor(
                    "BALANCE_DRAIN",
                    "Transfer would move more than "
                            + config.getBalanceFraction().multiply(new BigDecimal("100")).intValue()
                            + " percent of the account balance",
                    POINTS_BALANCE_DRAIN));
        }
    }

    /** Hour check — raises UNUSUAL_HOUR inside the configured unusual-activity window. */
    private void addUnusualHourFactor(List<RiskFactor> factors, Instant now) {
        int hour = now.atZone(zoneId).getHour();
        if (hour >= config.getOddHourStart() && hour < config.getOddHourEnd()) {
            factors.add(new RiskFactor(
                    "UNUSUAL_HOUR",
                    "Transfer was initiated at " + hour + ":00, inside the unusual activity window",
                    POINTS_UNUSUAL_HOUR));
        }
    }

    /** Verification-history check — raises REPEATED_FAILED_VERIFICATION after recent failures. */
    private void addRepeatedFailedVerificationFactor(
            List<RiskFactor> factors, Account sourceAccount, Instant now) {

        Long customerId = sourceAccount.getCustomer().getId();
        Instant since = now.minus(24, ChronoUnit.HOURS);
        long failures =
                verificationRequestRepository
                        .countByTransactionSourceAccountCustomerIdAndStatusAndCreatedAtAfter(
                                customerId, VerificationStatus.FAILED, since);

        if (failures >= 2) {
            factors.add(new RiskFactor(
                    "REPEATED_FAILED_VERIFICATION",
                    "Customer has failed verification " + failures + " times in the last 24 hours",
                    POINTS_REPEATED_FAILED_VERIFICATION));
        }
    }
}
