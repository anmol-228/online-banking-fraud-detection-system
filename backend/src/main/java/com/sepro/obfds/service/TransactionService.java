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
            AppProperties appProperties) {
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

    /** Initiates a fund transfer (FR-07). */
    @Transactional
    public TransactionResponse transfer(TransferRequest request) {
        Customer customer = currentUserService.requireCustomer();
        String username = currentUserService.requireUsername();
        List<String> authorities = currentUserService.currentAuthorities();

        // A repeated submission carrying the same key returns the original transfer rather than
        // creating a second one (TC-14).
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<Transaction> existing =
                    transactionRepository.findByIdempotencyKey(request.idempotencyKey().trim());
            if (existing.isPresent()) {
                return toResponse(existing.get(), hasOpenVerification(existing.get()));
            }
        }

        Account source = validateSourceAccount(request, customer, username, authorities);
        String destination = request.destinationAccountNumber().trim();
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
        transaction.setIdempotencyKey(blankToNull(request.idempotencyKey()));

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

    private Account validateSourceAccount(
            TransferRequest request, Customer customer, String username, List<String> authorities) {

        Account source = accountRepository
                .findByAccountNumberAndCustomerId(request.sourceAccountNumber().trim(), customer.getId())
                .orElseThrow(() -> {
                    auditService.failure(
                            username, authorities, AuditAction.TRANSFER_REJECTED, "Account",
                            request.sourceAccountNumber(), "Source account not found for this customer");
                    return new ResourceNotFoundException("Source account");
                });

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
                Instant.now().plus(Duration.ofMinutes(verificationConfig.getCodeValidityMinutes())));
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

    /** Submits the verification code for a held transfer (FR-14, FR-15). */
    @Transactional
    public TransactionResponse submitVerification(String reference, String submittedCode) {
        Customer customer = currentUserService.requireCustomer();
        String username = currentUserService.requireUsername();
        List<String> authorities = currentUserService.currentAuthorities();

        Transaction transaction = requireOwnTransaction(reference, customer);

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

        Instant now = Instant.now();
        if (verification.isExpired(now)) {
            verification.setStatus(VerificationStatus.EXPIRED);
            verificationRequestRepository.save(verification);
            blockTransaction(
                    transaction,
                    "The verification code expired before it was entered.",
                    username,
                    authorities);
            transactionRepository.save(transaction);
            throw new BusinessRuleException(
                    HttpStatus.GONE,
                    "VERIFICATION_EXPIRED",
                    "The verification code has expired and the transfer has been blocked.");
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

    /** Releases a held transfer after a fraud analyst approved it (FR-15). */
    @Transactional
    public Transaction releaseAfterReview(
            Transaction transaction, String remarks, String username, List<String> authorities) {

        requireHeld(transaction);
        approveTransaction(
                transaction, "Released by fraud review: " + remarks, username, authorities);
        return transactionRepository.save(transaction);
    }

    /** Blocks a held transfer after a fraud analyst rejected it (FR-15). */
    @Transactional
    public Transaction blockAfterReview(
            Transaction transaction, String remarks, String username, List<String> authorities) {

        requireHeld(transaction);
        blockTransaction(transaction, "Blocked by fraud review: " + remarks, username, authorities);
        return transactionRepository.save(transaction);
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

    public static TransactionResponse toResponse(Transaction transaction, boolean verificationRequired) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getReference(),
                transaction.getSourceAccount().getAccountNumber(),
                transaction.getDestinationAccountNumber(),
                transaction.getDestinationName(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDescription(),
                transaction.getStatus().name(),
                transaction.getStatusReason(),
                transaction.getRiskLevel().name(),
                transaction.getRiskScore(),
                transaction.getRiskReason(),
                verificationRequired,
                transaction.getInitiatedBy(),
                transaction.getCreatedAt(),
                transaction.getCompletedAt());
    }

    /** Exposed so the reporting module can reuse the same risk bands (FR-21). */
    public RiskLevel classify(int score) {
        return fraudRiskService.classify(score);
    }
}
