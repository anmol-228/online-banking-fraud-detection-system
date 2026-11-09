package com.sepro.obfds.service;

import com.sepro.obfds.audit.AuditAction;
import com.sepro.obfds.audit.AuditService;
import com.sepro.obfds.config.AppProperties;
import com.sepro.obfds.dto.PageResponse;
import com.sepro.obfds.dto.TransactionResponse;
import com.sepro.obfds.dto.TransferRequest;
import com.sepro.obfds.dto.VerificationStatusResponse;
import com.sepro.obfds.entity.Account;
import com.sepro.obfds.entity.AccountStatus;
import com.sepro.obfds.entity.AlertStatus;
import com.sepro.obfds.entity.Beneficiary;
import com.sepro.obfds.entity.CaseStatus;
import com.sepro.obfds.entity.Customer;
import com.sepro.obfds.entity.FraudAlert;
import com.sepro.obfds.entity.FraudCase;
import com.sepro.obfds.entity.NotificationType;
import com.sepro.obfds.entity.RiskLevel;
import com.sepro.obfds.entity.Transaction;
import com.sepro.obfds.entity.TransactionStatus;
import com.sepro.obfds.entity.VerificationRequest;
import com.sepro.obfds.entity.VerificationStatus;
import com.sepro.obfds.exception.BusinessRuleException;
import com.sepro.obfds.exception.DuplicateTransactionException;
import com.sepro.obfds.exception.IdempotencyKeyReusedException;
import com.sepro.obfds.exception.ResourceNotFoundException;
import com.sepro.obfds.fraud.FraudRiskService;
import com.sepro.obfds.fraud.RiskEvaluation;
import com.sepro.obfds.notification.NotificationService;
import com.sepro.obfds.repository.AccountRepository;
import com.sepro.obfds.repository.BeneficiaryRepository;
import com.sepro.obfds.repository.FraudAlertRepository;
import com.sepro.obfds.repository.FraudCaseRepository;
import com.sepro.obfds.repository.TransactionRepository;
import com.sepro.obfds.repository.VerificationRequestRepository;
import com.sepro.obfds.security.CurrentUserService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction module (FR-07, FR-09, FR-10, FR-22) and the workflow that connects it to the
 * fraud detection module (FR-11 to FR-16).
 *
 * <p>The workflow is deliberately simple to describe:</p>
 *
 * <pre>
 * validate -> assess risk -> LOW    : approve immediately
 *                         -> MEDIUM : hold and ask the customer for a verification code
 *                         -> HIGH   : hold and open a fraud case for an analyst
 * </pre>
 *
 * <p>Money only moves when a transfer reaches APPROVED, and that movement happens inside the same
 * database transaction as the status change. A transfer can therefore never report success while
 * the account update failed (NFR-02, NFR-09).</p>
 */
@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final FraudCaseRepository fraudCaseRepository;
    private final VerificationRequestRepository verificationRequestRepository;
    private final FraudRiskService fraudRiskService;
    private final NotificationService notificationService;
    private final AccountService accountService;
    private final CurrentUserService currentUserService;
    private final ReferenceGenerator referenceGenerator;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties.Verification verificationConfig;
    private final RowLocker rowLocker;
    private final Clock clock;
    private final int reviewTargetHours;

    /** Reason recorded when a hold times out; also what the customer sees for it. */
    static final String EXPIRED_REASON =
            "Verification code expired before it was used; the transfer was not sent.";

    /**
     * What the customer is told about a fraud-review decision. Deliberately fixed text: the analyst's
     * remarks are free text, can name or describe anyone, and are meant for staff. They stay in the
     * fraud case and in the case's audit entry, never in anything the customer can read.
     */
    static final String REVIEW_RELEASED_REASON = "Released by fraud review.";
    static final String REVIEW_BLOCKED_REASON = "Blocked by fraud review.";

    /** Window used to recognise the same transfer being submitted twice (TC-14). */
    private static final Duration DUPLICATE_WINDOW = Duration.ofSeconds(60);

    public TransactionService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            BeneficiaryRepository beneficiaryRepository,
            FraudAlertRepository fraudAlertRepository,
            FraudCaseRepository fraudCaseRepository,
            VerificationRequestRepository verificationRequestRepository,
            FraudRiskService fraudRiskService,
            NotificationService notificationService,
            AccountService accountService,
            CurrentUserService currentUserService,
            ReferenceGenerator referenceGenerator,
            AuditService auditService,
            PasswordEncoder passwordEncoder,
            AppProperties appProperties,
            RowLocker rowLocker,
            Clock clock) {
        this.rowLocker = rowLocker;
        this.clock = clock;
        this.reviewTargetHours = appProperties.getReview().getTargetHours();
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.fraudCaseRepository = fraudCaseRepository;
        this.verificationRequestRepository = verificationRequestRepository;
        this.fraudRiskService = fraudRiskService;
        this.notificationService = notificationService;
        this.accountService = accountService;
        this.currentUserService = currentUserService;
        this.referenceGenerator = referenceGenerator;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.verificationConfig = appProperties.getVerification();
    }

    // ------------------------------------------------------------------
    // Initiating a transfer
    // ------------------------------------------------------------------

    /**
     * Initiates a fund transfer (FR-07).
     *
     * <p>The method is a single critical section per source account:</p>
     * <ol>
     *   <li>an idempotency key that is already known is answered without touching any lock;</li>
     *   <li>the source account (and an internal destination) is write-locked, lowest id first;</li>
     *   <li>the key is checked <em>again</em>, because an identical request may have committed
     *       while this one waited for the lock;</li>
     *   <li>only then are funds, held amounts and risk read - so what is read cannot change
     *       underneath this request.</li>
     * </ol>
     *
     * <p>READ_COMMITTED is explicit because step 3 and the held-amount read must see rows that
     * other transactions committed while this one waited; MySQL's default REPEATABLE READ could
     * serve them from an older snapshot.</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse transfer(TransferRequest request) {
        Customer customer = currentUserService.requireCustomer();
        String username = currentUserService.requireUsername();
        List<String> authorities = currentUserService.currentAuthorities();
        String idempotencyKey = blankToNull(request.idempotencyKey());

        Optional<TransactionResponse> replay = replayKnownKey(idempotencyKey, customer, request);
        if (replay.isPresent()) {
            return replay.get();
        }

        String destination = request.destinationAccountNumber().trim();
        Account source = lockSourceAccount(request, customer, destination, username, authorities);

        replay = replayKnownKey(idempotencyKey, customer, request);
        if (replay.isPresent()) {
            return replay.get();
        }

        // Holds whose verification code has expired stop promising funds. Doing it here, under the
        // account lock, means the funds check below sees the true available balance and nobody
        // needs a background scheduler.
        expireStaleHolds(source.getId(), username, authorities);

        BigDecimal amount = request.amount();

        validateDestination(source, destination, username, authorities);
        detectDuplicateSubmission(request, source, destination, amount);
        validateSufficientFunds(source, amount, username, authorities);

        Beneficiary beneficiary = beneficiaryRepository
                .findByCustomerIdAndAccountNumber(customer.getId(), destination)
                .filter(Beneficiary::isActive)
                .orElse(null);

        Transaction transaction = new Transaction();
        transaction.setReference(referenceGenerator.transactionReference());
        transaction.setSourceAccount(source);
        transaction.setBeneficiary(beneficiary);
        transaction.setDestinationAccountNumber(destination);
        transaction.setDestinationName(resolveDestinationName(beneficiary, destination));
        transaction.setAmount(amount);
        transaction.setCurrency(source.getCurrency());
        transaction.setDescription(blankToNull(request.description()));
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setInitiatedBy(username);
        transaction.setIdempotencyKey(idempotencyKey);

        // Transaction monitoring and risk classification (FR-11, FR-12).
        //
        // The assessment happens before this transfer is stored. If the row were saved first, the
        // velocity rule would count the transfer against itself and every third transfer would be
        // scored as rapid activity.
        RiskEvaluation evaluation =
                fraudRiskService.evaluate(source, beneficiary, amount, Instant.now());
        transaction.setRiskScore(evaluation.score());
        transaction.setRiskLevel(evaluation.level());
        transaction.setRiskReason(evaluation.reason());

        transactionRepository.save(transaction);

        auditService.success(
                username, authorities, AuditAction.TRANSFER_INITIATED, "Transaction",
                transaction.getReference(),
                "Transfer of " + amount + " to " + destination + " initiated");

        auditService.success(
                username, authorities, AuditAction.RISK_EVALUATED, "Transaction",
                transaction.getReference(),
                "Risk " + evaluation.level() + " score " + evaluation.score() + ": " + evaluation.reason());

        boolean verificationRequired = false;
        switch (evaluation.level()) {
            case LOW -> approveTransaction(transaction, "Low risk transfer approved automatically", username, authorities);
            case MEDIUM -> {
                holdForVerification(transaction, evaluation, customer, username, authorities);
                verificationRequired = true;
            }
            case HIGH -> holdForFraudReview(transaction, evaluation, customer, username, authorities);
        }

        transactionRepository.save(transaction);
        return toResponse(transaction, verificationRequired);
    }

    /**
     * Answers a request whose idempotency key is already known, or returns empty if it is new.
     *
     * <p>A key is honoured only for its owner and only for the same request. The same customer with
     * the same source, destination and amount (compared as numbers, so {@code 1500} equals
     * {@code 1500.00}) gets the original answer back - safe to repeat after a timeout or a lost
     * response. Anything else is a {@link IdempotencyKeyReusedException}, whose message and body
     * reveal nothing about the transfer that owns the key.</p>
     */
    private Optional<TransactionResponse> replayKnownKey(
            String idempotencyKey, Customer customer, TransferRequest request) {

        if (idempotencyKey == null) {
            return Optional.empty();
        }
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        Transaction original = existing.get();
        boolean sameOwner = original.getSourceAccount().getCustomer().getId().equals(customer.getId());
        boolean samePayload = original.getSourceAccount().getAccountNumber().equals(request.sourceAccountNumber().trim())
                && original.getDestinationAccountNumber().equals(request.destinationAccountNumber().trim())
                && original.getAmount().compareTo(request.amount()) == 0;
        if (!sameOwner || !samePayload) {
            throw new IdempotencyKeyReusedException();
        }
        return Optional.of(toResponse(original, hasOpenVerification(original)));
    }

    /**
     * Locks the source account (and the destination when it is inside this bank) before anything
     * about the account is read, then validates it. Returns the locked, freshly read account.
     */
    private Account lockSourceAccount(
            TransferRequest request, Customer customer, String destination,
            String username, List<String> authorities) {

        Long sourceId = accountRepository
                .findIdByAccountNumberAndCustomerId(request.sourceAccountNumber().trim(), customer.getId())
                .orElseThrow(() -> {
                    auditService.failure(
                            username, authorities, AuditAction.TRANSFER_REJECTED, "Account",
                            request.sourceAccountNumber(), "Source account not found for this customer");
                    return new ResourceNotFoundException("Source account");
                });
        Long destinationId = accountRepository.findIdByAccountNumber(destination).orElse(null);
        Account source = rowLocker.lockAccounts(sourceId, destinationId).get(sourceId);

        if (source.getStatus() != AccountStatus.ACTIVE) {
            auditService.failure(
                    username, authorities, AuditAction.TRANSFER_REJECTED, "Account",
                    source.getAccountNumber(), "Source account status is " + source.getStatus());
            throw new BusinessRuleException(
                    "ACCOUNT_NOT_ACTIVE",
                    "This account is " + source.getStatus().name().toLowerCase()
                            + " and cannot be used for transfers.");
        }
        return source;
    }

    private void validateDestination(
            Account source, String destination, String username, List<String> authorities) {

        if (source.getAccountNumber().equals(destination)) {
            auditService.failure(
                    username, authorities, AuditAction.TRANSFER_REJECTED, "Account", destination,
                    "Source and destination accounts are the same");
            throw new BusinessRuleException(
                    "SAME_ACCOUNT_TRANSFER", "You cannot transfer money to the same account.");
        }
    }

    /** Rejects an identical transfer repeated within the duplicate window (TC-14, NFR-09). */
    private void detectDuplicateSubmission(
            TransferRequest request, Account source, String destination, BigDecimal amount) {

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            // The key already gave an exact answer above, so the heuristic is not needed.
            return;
        }
        Instant since = Instant.now().minus(DUPLICATE_WINDOW);
        transactionRepository
                .findFirstBySourceAccountIdAndDestinationAccountNumberAndAmountAndCreatedAtAfterOrderByIdDesc(
                        source.getId(), destination, amount, since)
                .ifPresent(existing -> {
                    throw new DuplicateTransactionException(existing.getReference());
                });
    }

    private void validateSufficientFunds(
            Account source, BigDecimal amount, String username, List<String> authorities) {

        BigDecimal available = accountService.availableBalance(source);
        if (available.compareTo(amount) < 0) {
            auditService.failure(
                    username, authorities, AuditAction.TRANSFER_REJECTED, "Account",
                    source.getAccountNumber(),
                    "Insufficient available balance: requested " + amount + ", available " + available);
            throw new BusinessRuleException(
                    "INSUFFICIENT_BALANCE",
                    "The available balance in this account is not sufficient for this transfer.");
        }
    }

    // ------------------------------------------------------------------
    // Outcomes of the risk decision
    // ------------------------------------------------------------------

    /**
     * Moves the money and marks the transfer approved (FR-15, FR-22).
     *
     * <p>The debit, the credit and the status change all happen inside the caller transaction, so
     * either all three are stored or none of them are (NFR-02).</p>
     */
    private void approveTransaction(
            Transaction transaction, String reason, String username, List<String> authorities) {

        Account source = transaction.getSourceAccount();

        // Defensive re-check. A transfer held for review may have been overtaken by other
        // approved transfers while it was waiting (TC-18).
        if (source.getBalance().compareTo(transaction.getAmount()) < 0) {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setStatusReason("Balance was no longer sufficient when the transfer was released.");
            transaction.setCompletedAt(Instant.now());
            auditService.failure(
                    username, authorities, AuditAction.TRANSFER_REJECTED, "Transaction",
                    transaction.getReference(), transaction.getStatusReason());
            notifyCustomer(
                    transaction,
                    NotificationType.TRANSACTION,
                    "Transfer failed",
                    "Transfer " + transaction.getReference() + " could not be completed because the "
                            + "account balance was no longer sufficient.");
            return;
        }

        source.adjustBalance(transaction.getAmount().negate());
        accountRepository.save(source);

        // When the destination is another account inside this simulation, credit it as well.
        // Transfers to any other account number are treated as leaving the simulated bank.
        accountRepository
                .findByAccountNumber(transaction.getDestinationAccountNumber())
                .filter(destination -> !destination.getId().equals(source.getId()))
                .ifPresent(destination -> {
                    destination.adjustBalance(transaction.getAmount());
                    accountRepository.save(destination);
                });

        transaction.setStatus(TransactionStatus.APPROVED);
        transaction.setStatusReason(reason);
        transaction.setCompletedAt(Instant.now());

        auditService.success(
                username, authorities, AuditAction.TRANSACTION_APPROVED, "Transaction",
                transaction.getReference(),
                "Transfer of " + transaction.getAmount() + " approved: " + reason);

        notifyCustomer(
                transaction,
                NotificationType.TRANSACTION,
                "Transfer completed",
                "Your transfer of " + transaction.getCurrency() + " " + transaction.getAmount()
                        + " to " + transaction.getDestinationName() + " has been completed. Reference "
                        + transaction.getReference() + ".");
    }

    /** MEDIUM risk: hold the transfer and challenge the customer for a code (FR-13, FR-14). */
    private void holdForVerification(
            Transaction transaction,
            RiskEvaluation evaluation,
            Customer customer,
            String username,
            List<String> authorities) {

        transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);
        transaction.setStatusReason("Additional verification is required before this transfer can proceed.");

        FraudAlert alert = raiseAlert(transaction, evaluation, username, authorities);
        alert.setStatus(AlertStatus.OPEN);

        String code = referenceGenerator.verificationCode();
        VerificationRequest verification = new VerificationRequest();
        verification.setTransaction(transaction);
        // Only the hash is stored, exactly as for a password (NFR-01).
        verification.setCodeHash(passwordEncoder.encode(code));
        verification.setStatus(VerificationStatus.PENDING);
        verification.setMaxAttempts(verificationConfig.getMaxAttempts());
        verification.setExpiresAt(
                clock.instant().plus(Duration.ofMinutes(verificationConfig.getCodeValidityMinutes())));
        verificationRequestRepository.save(verification);

        auditService.success(
                username, authorities, AuditAction.VERIFICATION_REQUESTED, "Transaction",
                transaction.getReference(),
                "Verification code issued, valid for " + verificationConfig.getCodeValidityMinutes()
                        + " minutes");

        // The code is delivered through the notification module because this simulation has no
        // SMS gateway. This is stated in the user manual so nobody mistakes it for a real channel.
        notificationService.notify(
                customer,
                NotificationType.VERIFICATION,
                "Verification code for transfer " + transaction.getReference(),
                "Your verification code is " + code + ". It is valid for "
                        + verificationConfig.getCodeValidityMinutes() + " minutes. Enter it to release "
                        + "your transfer of " + transaction.getCurrency() + " " + transaction.getAmount()
                        + " to " + transaction.getDestinationName() + ".",
                transaction.getReference());
    }

    /** HIGH risk: hold the transfer and open a case for a fraud analyst (FR-13, FR-15, FR-18). */
    private void holdForFraudReview(
            Transaction transaction,
            RiskEvaluation evaluation,
            Customer customer,
            String username,
            List<String> authorities) {

        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setStatusReason("Held for fraud review because the transfer was classified as high risk.");

        FraudAlert alert = raiseAlert(transaction, evaluation, username, authorities);
        alert.setStatus(AlertStatus.UNDER_REVIEW);

        FraudCase fraudCase = new FraudCase();
        fraudCase.setReference(referenceGenerator.caseReference());
        fraudCase.setAlert(alert);
        fraudCase.setStatus(CaseStatus.OPEN);
        // A TARGET for the review, shown to the customer and the analyst. Nothing happens when it
        // passes: there is no automatic release and no scheduler enforcing it.
        fraudCase.setReviewDueAt(clock.instant().plus(Duration.ofHours(reviewTargetHours)));
        fraudCaseRepository.save(fraudCase);

        auditService.success(
                username, authorities, AuditAction.FRAUD_CASE_OPENED, "FraudCase",
                fraudCase.getReference(),
                "Case opened automatically for high risk transaction " + transaction.getReference());

        notifyCustomer(
                transaction,
                NotificationType.FRAUD_ALERT,
                "Transfer held for review",
                "Your transfer of " + transaction.getCurrency() + " " + transaction.getAmount()
                        + " to " + transaction.getDestinationName() + " (reference "
                        + transaction.getReference() + ") has been held for a security review. "
                        + "You will be notified once the review is complete.");
    }

    private FraudAlert raiseAlert(
            Transaction transaction, RiskEvaluation evaluation, String username, List<String> authorities) {

        FraudAlert alert = new FraudAlert();
        alert.setReference(referenceGenerator.alertReference());
        alert.setTransaction(transaction);
        alert.setRiskLevel(evaluation.level());
        alert.setRiskScore(evaluation.score());
        alert.setReason(trim(evaluation.reason(), 500));
        alert.setStatus(AlertStatus.OPEN);
        fraudAlertRepository.save(alert);

        auditService.success(
                username, authorities, AuditAction.FRAUD_ALERT_RAISED, "FraudAlert",
                alert.getReference(),
                "Alert raised for transaction " + transaction.getReference() + " at risk "
                        + evaluation.level());
        return alert;
    }

    // ------------------------------------------------------------------
    // Additional verification by the customer
    // ------------------------------------------------------------------

    /**
     * Submits the verification code for a held transfer (FR-14, FR-15).
     *
     * <p>{@code noRollbackFor} is essential rather than decorative here. Every failure path in
     * this method reports itself by throwing a {@link BusinessRuleException}, and three of them
     * must leave a permanent record behind first: the incremented attempt counter, the FAILED
     * verification status, and the block applied once the attempts run out. Under the default
     * rollback-on-runtime-exception rule those writes were discarded along with the exception, so
     * the attempt counter never advanced past zero, the three-attempt limit never fired, and a
     * held transfer could be guessed at indefinitely. The exceptions raised before any write
     * (INVALID_STATE, NO_PENDING_VERIFICATION) simply commit an empty transaction, so exempting
     * the whole type is safe.</p>
     */
    @Transactional(noRollbackFor = BusinessRuleException.class, isolation = Isolation.READ_COMMITTED)
    public TransactionResponse submitVerification(String reference, String submittedCode) {
        Customer customer = currentUserService.requireCustomer();
        String username = currentUserService.requireUsername();
        List<String> authorities = currentUserService.currentAuthorities();

        Transaction transaction = requireOwnTransaction(reference, customer);

        // Same critical section as a new transfer: approving moves money, and the attempt counter
        // must be read and written under the lock so two parallel guesses cannot both count as
        // "attempt 1". The transaction is re-read afterwards because its status may have changed
        // (cancelled, blocked) while this request waited.
        lockAccountsOf(transaction);
        rowLocker.reload(transaction);

        if (transaction.getStatus() != TransactionStatus.PENDING_VERIFICATION) {
            throw new BusinessRuleException(
                    "INVALID_STATE",
                    "This transfer is not waiting for verification. Its current status is "
                            + transaction.getStatus() + ".");
        }

        VerificationRequest verification = verificationRequestRepository
                .findFirstByTransactionIdAndStatusOrderByIdDesc(
                        transaction.getId(), VerificationStatus.PENDING)
                .orElseThrow(() -> new BusinessRuleException(
                        "NO_PENDING_VERIFICATION",
                        "There is no verification request waiting for this transfer."));

        Instant now = clock.instant();
        if (verification.isExpired(now)) {
            // Nobody judged this transfer fraudulent - it timed out. It therefore ends FAILED
            // (not BLOCKED), the alert it raised is closed, and the held funds are freed.
            expireHold(transaction, username, authorities);
            throw new BusinessRuleException(
                    HttpStatus.GONE,
                    "VERIFICATION_EXPIRED",
                    "The verification code has expired and the transfer was not sent. "
                            + "No money left your account.");
        }

        if (!passwordEncoder.matches(submittedCode, verification.getCodeHash())) {
            verification.recordFailedAttempt();

            if (!verification.hasAttemptsLeft()) {
                verification.setStatus(VerificationStatus.FAILED);
                verificationRequestRepository.save(verification);

                auditService.failure(
                        username, authorities, AuditAction.VERIFICATION_FAILED, "Transaction",
                        transaction.getReference(), "All verification attempts were used");

                blockTransaction(
                        transaction,
                        "Verification failed after " + verification.getMaxAttempts() + " attempts.",
                        username,
                        authorities);
                escalateAlertToCase(transaction, username, authorities);
                transactionRepository.save(transaction);

                throw new BusinessRuleException(
                        "VERIFICATION_FAILED",
                        "The transfer has been blocked because the verification code was entered "
                                + "incorrectly too many times.");
            }

            verificationRequestRepository.save(verification);
            auditService.failure(
                    username, authorities, AuditAction.VERIFICATION_FAILED, "Transaction",
                    transaction.getReference(),
                    "Incorrect code, attempt " + verification.getAttempts() + " of "
                            + verification.getMaxAttempts());

            int remaining = verification.getMaxAttempts() - verification.getAttempts();
            throw new BusinessRuleException(
                    "INVALID_VERIFICATION_CODE",
                    "That verification code is not correct. You have " + remaining
                            + " attempt(s) remaining.");
        }

        verification.setStatus(VerificationStatus.VERIFIED);
        verification.setVerifiedAt(now);
        verificationRequestRepository.save(verification);

        auditService.success(
                username, authorities, AuditAction.VERIFICATION_SUCCESS, "Transaction",
                transaction.getReference(), "Verification completed successfully");

        approveTransaction(
                transaction, "Released after successful additional verification.", username, authorities);
        closeAlert(transaction);
        transactionRepository.save(transaction);

        return toResponse(transaction, false);
    }

    // ------------------------------------------------------------------
    // Expiry of unused verification codes
    // ------------------------------------------------------------------

    private void expireStaleHolds(Long accountId, String username, List<String> authorities) {
        for (Transaction stale : transactionRepository.findExpiredVerificationHolds(accountId, clock.instant())) {
            expireHold(stale, username, authorities);
        }
    }

    /** Ends a hold whose code timed out: verification EXPIRED, transfer FAILED, alert CLOSED. */
    private void expireHold(Transaction transaction, String username, List<String> authorities) {
        verificationRequestRepository
                .findFirstByTransactionIdAndStatusOrderByIdDesc(transaction.getId(), VerificationStatus.PENDING)
                .ifPresent(verification -> {
                    verification.setStatus(VerificationStatus.EXPIRED);
                    verificationRequestRepository.save(verification);
                });
        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setStatusReason(EXPIRED_REASON);
        transaction.setCompletedAt(clock.instant());
        transactionRepository.save(transaction);
        closeAlert(transaction);

        auditService.success(
                username, authorities, AuditAction.VERIFICATION_EXPIRED, "Transaction",
                transaction.getReference(), EXPIRED_REASON);
        notifyCustomer(
                transaction,
                NotificationType.TRANSACTION,
                "Transfer not completed",
                "Your transfer of " + transaction.getCurrency() + " " + transaction.getAmount() + " to "
                        + transaction.getDestinationName() + " (reference " + transaction.getReference()
                        + ") was not sent because its verification code expired. "
                        + "No money left your account.");
    }

    // ------------------------------------------------------------------
    // The customer's own actions on a held transfer
    // ------------------------------------------------------------------

    /**
     * Lets a customer cancel a transfer that is still held (PENDING or PENDING_VERIFICATION).
     *
     * <p>This is NOT a fraud finding. The transfer ends FAILED with an explicit reason, the funds
     * are released, and a related case is closed with a remark saying exactly that, so a cancelled
     * legitimate payment is never read as confirmed fraud. Lock order is case first, then accounts
     * (see {@link RowLocker}); that is what makes a cancel racing an analyst's approval end with
     * exactly one winner.</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse cancel(String reference) {
        Customer customer = currentUserService.requireCustomer();
        String username = currentUserService.requireUsername();
        List<String> authorities = currentUserService.currentAuthorities();

        Transaction transaction = requireOwnTransaction(reference, customer);
        FraudCase fraudCase = caseOf(transaction)
                .map(existing -> rowLocker.lockCase(existing.getReference()))
                .orElse(null);
        lockAccountsOf(transaction);
        rowLocker.reload(transaction);

        if (transaction.getStatus() == TransactionStatus.PENDING_VERIFICATION
                && verificationRequestRepository
                        .findFirstByTransactionIdAndStatusOrderByIdDesc(transaction.getId(), VerificationStatus.PENDING)
                        .map(verification -> verification.isExpired(clock.instant()))
                        .orElse(false)) {
            expireHold(transaction, username, authorities);
        }
        if (!transaction.isReserving()) {
            throw new BusinessRuleException(
                    "INVALID_STATE",
                    "This transfer is already " + transaction.getStatus().name().toLowerCase()
                            + " and can no longer be cancelled.");
        }

        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setStatusReason("Cancelled by customer before release.");
        transaction.setCompletedAt(clock.instant());
        transactionRepository.save(transaction);
        closeAlert(transaction);

        if (fraudCase != null
                && fraudCase.getStatus() != CaseStatus.RESOLVED_APPROVED
                && fraudCase.getStatus() != CaseStatus.RESOLVED_BLOCKED) {
            fraudCase.setStatus(CaseStatus.RESOLVED_BLOCKED);
            fraudCase.setRemarks("Cancelled by the customer before review. This is not a fraud finding.");
            fraudCase.setDecidedBy("customer-cancel");
            fraudCase.setClosedAt(clock.instant());
            fraudCaseRepository.save(fraudCase);
        }

        auditService.success(
                username, authorities, AuditAction.CUSTOMER_CANCELLED, "Transaction",
                transaction.getReference(), "Customer cancelled the held transfer before release");
        notifyCustomer(
                transaction,
                NotificationType.TRANSACTION,
                "Transfer cancelled",
                "Your transfer of " + transaction.getCurrency() + " " + transaction.getAmount() + " to "
                        + transaction.getDestinationName() + " (reference " + transaction.getReference()
                        + ") was cancelled at your request. No money left your account.");
        return toResponse(transaction, false);
    }

    /**
     * Records what the account holder says about a held HIGH-risk transfer: {@code CONFIRMED_MINE}
     * or {@code NOT_MINE}.
     *
     * <p>This is EVIDENCE for the analyst and nothing more. It is a statement made in the same
     * session as the transfer and is not independent identity verification, so it never releases
     * money. It is written once; the only change allowed afterwards is from "mine" to "not mine",
     * because a report of fraud outranks a confirmation. "Not mine" also forbids approval (see
     * {@code FraudCaseService.decide}).</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse recordCustomerResponse(String reference, String response) {
        Customer customer = currentUserService.requireCustomer();
        String username = currentUserService.requireUsername();
        List<String> authorities = currentUserService.currentAuthorities();

        Transaction transaction = requireOwnTransaction(reference, customer);
        FraudCase found = caseOf(transaction).orElseThrow(() -> new BusinessRuleException(
                "NO_REVIEW_CASE",
                "This transfer is not under fraud review, so there is nothing to confirm."));
        FraudCase fraudCase = rowLocker.lockCase(found.getReference());
        rowLocker.reload(transaction);

        if (transaction.isFinal()
                || fraudCase.getStatus() == CaseStatus.RESOLVED_APPROVED
                || fraudCase.getStatus() == CaseStatus.RESOLVED_BLOCKED) {
            throw new BusinessRuleException("INVALID_STATE", "This review is already finished.");
        }
        String current = fraudCase.getCustomerResponse();
        boolean upgradeToNotMine = "CONFIRMED_MINE".equals(current) && "NOT_MINE".equals(response);
        if (current != null && !upgradeToNotMine) {
            throw new BusinessRuleException(
                    HttpStatus.CONFLICT, "RESPONSE_ALREADY_RECORDED",
                    "Your statement about this transfer was already recorded and cannot be changed.");
        }

        fraudCase.setCustomerResponse(response);
        fraudCase.setCustomerRespondedAt(clock.instant());
        fraudCaseRepository.save(fraudCase);

        auditService.success(
                username, authorities, AuditAction.CUSTOMER_RESPONSE_RECORDED, "FraudCase",
                fraudCase.getReference(),
                "Customer statement recorded: " + response
                        + " (unverified same-session statement; evidence only, releases nothing)");
        return toResponse(transaction, false);
    }

    private Optional<FraudCase> caseOf(Transaction transaction) {
        return fraudAlertRepository
                .findFirstByTransactionIdOrderByIdDesc(transaction.getId())
                .flatMap(alert -> fraudCaseRepository.findByAlertId(alert.getId()));
    }

    /** State of the outstanding verification challenge, used by the verification screen. */
    @Transactional(readOnly = true)
    public VerificationStatusResponse getVerificationStatus(String reference) {
        Customer customer = currentUserService.requireCustomer();
        Transaction transaction = requireOwnTransaction(reference, customer);

        VerificationRequest verification = verificationRequestRepository
                .findFirstByTransactionIdOrderByIdDesc(transaction.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Verification request"));

        return new VerificationStatusResponse(
                transaction.getReference(),
                verification.getStatus().name(),
                verification.getAttempts(),
                verification.getMaxAttempts(),
                Math.max(0, verification.getMaxAttempts() - verification.getAttempts()),
                verification.getExpiresAt());
    }

    // ------------------------------------------------------------------
    // Settlement used by the fraud review module
    // ------------------------------------------------------------------

    /**
     * Releases a held transfer after a fraud analyst approved it (FR-15).
     *
     * <p>Callers hold the case lock already (case first, then accounts - see {@link RowLocker}).
     * The accounts are locked here, and the transaction is re-read under that lock, so the funds
     * check inside {@code approveTransaction} is made against the committed balance. Check
     * {@link Transaction#getStatus()} afterwards: a release can end FAILED, and a caller must derive
     * its own outcome from that, not assume APPROVED.</p>
     */
    @Transactional
    public Transaction releaseAfterReview(
            Transaction transaction, String username, List<String> authorities) {

        lockAccountsOf(transaction);
        rowLocker.reload(transaction);
        requireHeld(transaction);
        approveTransaction(transaction, REVIEW_RELEASED_REASON, username, authorities);
        return transactionRepository.save(transaction);
    }

    /** Blocks a held transfer after a fraud analyst rejected it (FR-15). */
    @Transactional
    public Transaction blockAfterReview(
            Transaction transaction, String username, List<String> authorities) {

        lockAccountsOf(transaction);
        rowLocker.reload(transaction);
        requireHeld(transaction);
        blockTransaction(transaction, REVIEW_BLOCKED_REASON, username, authorities);
        return transactionRepository.save(transaction);
    }

    /** Locks the source account and an in-bank destination of an existing transfer, lowest id first. */
    private void lockAccountsOf(Transaction transaction) {
        Long sourceId = transaction.getSourceAccount().getId();
        Long destinationId =
                accountRepository.findIdByAccountNumber(transaction.getDestinationAccountNumber()).orElse(null);
        rowLocker.lockAccounts(sourceId, destinationId);
    }

    private void requireHeld(Transaction transaction) {
        if (transaction.isFinal()) {
            throw new BusinessRuleException(
                    "INVALID_STATE",
                    "This transfer is already " + transaction.getStatus()
                            + " and can no longer be changed.");
        }
    }

    private void blockTransaction(
            Transaction transaction, String reason, String username, List<String> authorities) {

        transaction.setStatus(TransactionStatus.BLOCKED);
        transaction.setStatusReason(reason);
        transaction.setCompletedAt(Instant.now());

        auditService.success(
                username, authorities, AuditAction.TRANSACTION_BLOCKED, "Transaction",
                transaction.getReference(), reason);

        notifyCustomer(
                transaction,
                NotificationType.SECURITY,
                "Transfer blocked",
                "Your transfer of " + transaction.getCurrency() + " " + transaction.getAmount()
                        + " to " + transaction.getDestinationName() + " (reference "
                        + transaction.getReference() + ") has been blocked. " + reason);
    }

    /** A failed verification is itself a fraud signal, so the alert becomes a case (FR-18). */
    private void escalateAlertToCase(
            Transaction transaction, String username, List<String> authorities) {

        fraudAlertRepository
                .findFirstByTransactionIdOrderByIdDesc(transaction.getId())
                .ifPresent(alert -> {
                    if (fraudCaseRepository.findByAlertId(alert.getId()).isPresent()) {
                        return;
                    }
                    alert.setStatus(AlertStatus.UNDER_REVIEW);
                    fraudAlertRepository.save(alert);

                    FraudCase fraudCase = new FraudCase();
                    fraudCase.setReference(referenceGenerator.caseReference());
                    fraudCase.setAlert(alert);
                    fraudCase.setStatus(CaseStatus.OPEN);
                    fraudCase.setRemarks("Opened automatically after repeated verification failure.");
                    fraudCaseRepository.save(fraudCase);

                    auditService.success(
                            username, authorities, AuditAction.FRAUD_CASE_OPENED, "FraudCase",
                            fraudCase.getReference(),
                            "Case opened after verification failure on " + transaction.getReference());
                });
    }

    private void closeAlert(Transaction transaction) {
        fraudAlertRepository
                .findFirstByTransactionIdOrderByIdDesc(transaction.getId())
                .ifPresent(alert -> {
                    alert.setStatus(AlertStatus.CLOSED);
                    fraudAlertRepository.save(alert);
                });
    }

    // ------------------------------------------------------------------
    // Reading transactions
    // ------------------------------------------------------------------

    /** Transaction history for the caller, newest first (FR-10). */
    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> myTransactions(int page, int size) {
        Customer customer = currentUserService.requireCustomer();
        PageRequest pageRequest =
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Transaction> results = transactionRepository.findByCustomer(customer.getId(), pageRequest);
        List<TransactionResponse> content =
                results.getContent().stream().map(txn -> toResponse(txn, hasOpenVerification(txn))).toList();
        return PageResponse.from(results, content);
    }

    @Transactional(readOnly = true)
    public TransactionResponse myTransaction(String reference) {
        Customer customer = currentUserService.requireCustomer();
        Transaction transaction = requireOwnTransaction(reference, customer);
        return toResponse(transaction, hasOpenVerification(transaction));
    }

    private Transaction requireOwnTransaction(String reference, Customer customer) {
        Transaction transaction = transactionRepository
                .findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", reference));

        // Answering NOT_FOUND rather than FORBIDDEN avoids confirming that a reference belonging
        // to another customer exists (NFR-08).
        if (!transaction.getSourceAccount().getCustomer().getId().equals(customer.getId())) {
            throw new ResourceNotFoundException("Transaction", reference);
        }
        return transaction;
    }

    private boolean hasOpenVerification(Transaction transaction) {
        return transaction.getStatus() == TransactionStatus.PENDING_VERIFICATION
                && verificationRequestRepository
                        .findFirstByTransactionIdAndStatusOrderByIdDesc(
                                transaction.getId(), VerificationStatus.PENDING)
                        .isPresent();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void notifyCustomer(
            Transaction transaction, NotificationType type, String title, String message) {
        notificationService.notify(
                transaction.getSourceAccount().getCustomer(),
                type,
                title,
                message,
                transaction.getReference());
    }

    private String resolveDestinationName(Beneficiary beneficiary, String destinationAccountNumber) {
        if (beneficiary != null) {
            return beneficiary.getName();
        }
        return accountRepository
                .findByAccountNumber(destinationAccountNumber)
                .map(account -> account.getCustomer().getFullName())
                .orElse("Account " + destinationAccountNumber);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    /**
     * The customer-facing view of a transfer.
     *
     * <p>A hold whose verification code has expired is shown as FAILED even before anything has
     * swept it, so the screen, the funds shown as available, and the operational report all tell
     * the same story. A HIGH-risk hold also carries the review TARGET time and whatever the
     * customer has said about it.</p>
     */
    public TransactionResponse toResponse(Transaction transaction, boolean verificationRequired) {
        boolean expired = transaction.getStatus() == TransactionStatus.PENDING_VERIFICATION
                && verificationRequestRepository
                        .findFirstByTransactionIdAndStatusOrderByIdDesc(transaction.getId(), VerificationStatus.PENDING)
                        .map(verification -> verification.isExpired(clock.instant()))
                        .orElse(false);

        Instant reviewDueAt = null;
        String customerResponse = null;
        if (transaction.getStatus() == TransactionStatus.PENDING && transaction.getRiskLevel() == RiskLevel.HIGH) {
            Optional<FraudCase> fraudCase = caseOf(transaction);
            reviewDueAt = fraudCase.map(FraudCase::getReviewDueAt).orElse(null);
            customerResponse = fraudCase.map(FraudCase::getCustomerResponse).orElse(null);
        }

        return new TransactionResponse(
                transaction.getId(),
                transaction.getReference(),
                transaction.getSourceAccount().getAccountNumber(),
                transaction.getDestinationAccountNumber(),
                transaction.getDestinationName(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDescription(),
                expired ? TransactionStatus.FAILED.name() : transaction.getStatus().name(),
                expired ? EXPIRED_REASON : transaction.getStatusReason(),
                transaction.getRiskLevel().name(),
                transaction.getRiskScore(),
                transaction.getRiskReason(),
                verificationRequired && !expired,
                transaction.getInitiatedBy(),
                transaction.getCreatedAt(),
                transaction.getCompletedAt(),
                reviewDueAt,
                customerResponse);
    }
    /** Exposed so the reporting module can reuse the same risk bands (FR-21). */
    public RiskLevel classify(int score) {
        return fraudRiskService.classify(score);
    }
}
