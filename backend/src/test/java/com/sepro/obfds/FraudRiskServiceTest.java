package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.sepro.obfds.config.AppProperties;
import com.sepro.obfds.entity.Account;
import com.sepro.obfds.entity.Beneficiary;
import com.sepro.obfds.entity.Customer;
import com.sepro.obfds.entity.RiskLevel;
import com.sepro.obfds.entity.VerificationStatus;
import com.sepro.obfds.fraud.FraudRiskService;
import com.sepro.obfds.fraud.RiskEvaluation;
import com.sepro.obfds.fraud.RiskFactor;
import com.sepro.obfds.repository.TransactionRepository;
import com.sepro.obfds.repository.VerificationRequestRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for the fraud detection module (FR-11, FR-12).
 *
 * <p>These are plain unit tests with mocked repositories, so each scoring rule can be checked in
 * isolation. Covers TC-08 and the individual rules behind it.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FraudRiskServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private VerificationRequestRepository verificationRequestRepository;

    private FraudRiskService fraudRiskService;
    private Customer customer;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        // The unusual-hour rule is switched off here so the test result does not depend on the
        // time of day the test suite happens to run.
        properties.getFraud().setOddHourStart(0);
        properties.getFraud().setOddHourEnd(0);

        fraudRiskService =
                new FraudRiskService(properties, transactionRepository, verificationRequestRepository);

        customer = new Customer();
        setId(customer, 1L);

        when(transactionRepository.countBySourceAccountCustomerIdAndCreatedAtAfter(anyLong(), any()))
                .thenReturn(0L);
        when(verificationRequestRepository
                        .countByTransactionSourceAccountCustomerIdAndStatusAndCreatedAtAfter(
                                anyLong(), any(VerificationStatus.class), any()))
                .thenReturn(0L);
    }

    private Account account(String balance) {
        Account account = new Account();
        account.setAccountNumber("900000000001");
        account.setCustomer(customer);
        account.setBalance(new BigDecimal(balance));
        return account;
    }

    private Beneficiary beneficiary(Instant createdAt) {
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setName("Meera Nair");
        beneficiary.setAccountNumber("900000000003");
        beneficiary.setBankName("Demo Bank");
        beneficiary.setCreatedAt(createdAt);
        return beneficiary;
    }

    private Instant oldBeneficiaryDate(Instant now) {
        return now.minus(30, ChronoUnit.DAYS);
    }

    private List<String> codes(RiskEvaluation evaluation) {
        return evaluation.factors().stream().map(RiskFactor::code).toList();
    }

    @Test
    @DisplayName("TC-08 An ordinary transfer to an established payee is classified LOW")
    void ordinaryTransferIsLowRisk() {
        Instant now = Instant.now();
        RiskEvaluation evaluation = fraudRiskService.evaluate(
                account("150000.00"), beneficiary(oldBeneficiaryDate(now)), new BigDecimal("2500.00"), now);

        assertThat(evaluation.level()).isEqualTo(RiskLevel.LOW);
        assertThat(evaluation.score()).isZero();
        assertThat(evaluation.factors()).isEmpty();
        assertThat(evaluation.requiresAlert()).isFalse();
        assertThat(evaluation.reason()).isEqualTo("No risk indicators were triggered.");
    }

    @Test
    @DisplayName("TC-08 A large transfer alone is classified MEDIUM")
    void largeAmountIsMediumRisk() {
        Instant now = Instant.now();
        RiskEvaluation evaluation = fraudRiskService.evaluate(
                account("500000.00"), beneficiary(oldBeneficiaryDate(now)), new BigDecimal("60000.00"), now);

        assertThat(codes(evaluation)).containsExactly("HIGH_AMOUNT");
        assertThat(evaluation.score()).isEqualTo(35);
        assertThat(evaluation.level()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(evaluation.requiresAlert()).isTrue();
    }

    @Test
    @DisplayName("TC-08 A very large transfer to a brand new payee is classified HIGH")
    void veryLargeAmountToNewBeneficiaryIsHighRisk() {
        Instant now = Instant.now();
        RiskEvaluation evaluation = fraudRiskService.evaluate(
                account("500000.00"), beneficiary(now.minus(1, ChronoUnit.HOURS)),
                new BigDecimal("120000.00"), now);

        assertThat(codes(evaluation)).containsExactly("VERY_HIGH_AMOUNT", "NEW_BENEFICIARY");
        assertThat(evaluation.score()).isEqualTo(75);
        assertThat(evaluation.level()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    @DisplayName("FR-11 A transfer to an account that is not a saved payee scores extra")
    void unregisteredPayeeScores() {
        Instant now = Instant.now();
        RiskEvaluation evaluation =
                fraudRiskService.evaluate(account("150000.00"), null, new BigDecimal("1000.00"), now);

        assertThat(codes(evaluation)).containsExactly("UNREGISTERED_PAYEE");
        assertThat(evaluation.score()).isEqualTo(20);
        assertThat(evaluation.level()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("FR-11 Several transfers in a short period trigger the velocity rule")
    void velocityRuleFires() {
        Instant now = Instant.now();
        when(transactionRepository.countBySourceAccountCustomerIdAndCreatedAtAfter(anyLong(), any()))
                .thenReturn(4L);

        RiskEvaluation evaluation = fraudRiskService.evaluate(
                account("150000.00"), beneficiary(oldBeneficiaryDate(now)), new BigDecimal("1000.00"), now);

        assertThat(codes(evaluation)).contains("RAPID_TRANSFERS");
        assertThat(evaluation.score()).isEqualTo(20);
    }

    @Test
    @DisplayName("FR-11 A transfer that would drain the account triggers the balance rule")
    void balanceDrainRuleFires() {
        Instant now = Instant.now();
        RiskEvaluation evaluation = fraudRiskService.evaluate(
                account("10000.00"), beneficiary(oldBeneficiaryDate(now)), new BigDecimal("9500.00"), now);

        assertThat(codes(evaluation)).containsExactly("BALANCE_DRAIN");
        assertThat(evaluation.score()).isEqualTo(15);
    }

    @Test
    @DisplayName("FR-11 Repeated failed verifications raise the score of later transfers")
    void repeatedFailedVerificationRuleFires() {
        Instant now = Instant.now();
        when(verificationRequestRepository
                        .countByTransactionSourceAccountCustomerIdAndStatusAndCreatedAtAfter(
                                anyLong(), any(VerificationStatus.class), any()))
                .thenReturn(3L);

        RiskEvaluation evaluation = fraudRiskService.evaluate(
                account("150000.00"), beneficiary(oldBeneficiaryDate(now)), new BigDecimal("1000.00"), now);

        assertThat(codes(evaluation)).contains("REPEATED_FAILED_VERIFICATION");
        assertThat(evaluation.score()).isEqualTo(20);
    }

    @Test
    @DisplayName("FR-11 The unusual hour rule fires inside the configured window")
    void unusualHourRuleFires() {
        AppProperties properties = new AppProperties();
        properties.getFraud().setOddHourStart(0);
        properties.getFraud().setOddHourEnd(24);
        FraudRiskService alwaysOddHour =
                new FraudRiskService(properties, transactionRepository, verificationRequestRepository);

        Instant now = Instant.now();
        RiskEvaluation evaluation = alwaysOddHour.evaluate(
                account("150000.00"), beneficiary(oldBeneficiaryDate(now)), new BigDecimal("1000.00"), now);

        assertThat(codes(evaluation)).containsExactly("UNUSUAL_HOUR");
        assertThat(evaluation.score()).isEqualTo(10);
    }

    @Test
    @DisplayName("TC-08 The score bands map to LOW, MEDIUM and HIGH exactly as documented")
    void scoreBandsMatchDocumentation() {
        assertThat(fraudRiskService.classify(0)).isEqualTo(RiskLevel.LOW);
        assertThat(fraudRiskService.classify(29)).isEqualTo(RiskLevel.LOW);
        assertThat(fraudRiskService.classify(30)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(fraudRiskService.classify(59)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(fraudRiskService.classify(60)).isEqualTo(RiskLevel.HIGH);
        assertThat(fraudRiskService.classify(140)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    @DisplayName("FR-12 Evaluating the same transfer twice gives the same result")
    void evaluationIsDeterministic() {
        Instant now = Instant.now();
        Account account = account("150000.00");
        Beneficiary payee = beneficiary(oldBeneficiaryDate(now));

        RiskEvaluation first = fraudRiskService.evaluate(account, payee, new BigDecimal("60000.00"), now);
        RiskEvaluation second = fraudRiskService.evaluate(account, payee, new BigDecimal("60000.00"), now);

        assertThat(first.score()).isEqualTo(second.score());
        assertThat(first.level()).isEqualTo(second.level());
        assertThat(first.reason()).isEqualTo(second.reason());
    }

    /** The identifier is normally assigned by the database, so it is set reflectively here. */
    private void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not set test identifier", ex);
        }
    }
}
