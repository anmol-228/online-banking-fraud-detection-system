package com.sepro.obfds.service;

import com.sepro.obfds.audit.AuditAction;
import com.sepro.obfds.audit.AuditService;
import com.sepro.obfds.dto.CaseDecisionRequest;
import com.sepro.obfds.dto.FraudCaseResponse;
import com.sepro.obfds.entity.AlertStatus;
import com.sepro.obfds.entity.CaseStatus;
import com.sepro.obfds.entity.FraudAlert;
import com.sepro.obfds.entity.FraudCase;
import com.sepro.obfds.entity.Transaction;
import com.sepro.obfds.entity.TransactionStatus;
import com.sepro.obfds.exception.BusinessRuleException;
import com.sepro.obfds.exception.ResourceNotFoundException;
import com.sepro.obfds.repository.FraudAlertRepository;
import com.sepro.obfds.repository.FraudCaseRepository;
import com.sepro.obfds.security.CurrentUserService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The fraud and case review module (FR-15, FR-18).
 *
 * <p>A decision recorded here is what finally releases or blocks a held transfer, so the decision
 * and the resulting transaction status change happen in the same database transaction.</p>
 */
@Service
public class FraudCaseService {

    private final FraudCaseRepository fraudCaseRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final TransactionService transactionService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final RowLocker rowLocker;

    public FraudCaseService(
            FraudCaseRepository fraudCaseRepository,
            FraudAlertRepository fraudAlertRepository,
            TransactionService transactionService,
            CurrentUserService currentUserService,
            AuditService auditService,
            RowLocker rowLocker) {
        this.rowLocker = rowLocker;
        this.fraudCaseRepository = fraudCaseRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.transactionService = transactionService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<FraudCaseResponse> listCases() {
        return fraudCaseRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public FraudCaseResponse getCase(String reference) {
        return toResponse(requireCase(reference));
    }

    /** Claims a case so that two analysts do not work on the same alert (FR-18). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FraudCaseResponse assignToMe(String reference) {
        FraudCase fraudCase = rowLocker.lockCase(reference);
        requireOpenCase(fraudCase);

        fraudCase.setAssignedTo(currentUserService.requireUsername());
        fraudCase.setStatus(CaseStatus.UNDER_REVIEW);

        FraudAlert alert = fraudCase.getAlert();
        alert.setStatus(AlertStatus.UNDER_REVIEW);
        fraudAlertRepository.save(alert);

        return toResponse(fraudCaseRepository.save(fraudCase));
    }

    /**
     * Records the analyst decision and applies it to the held transfer (FR-15).
     *
     * <p>APPROVE releases the transfer and the money moves. BLOCK stops it permanently. Either
     * way the customer is notified and the audit trail records who decided and why.</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FraudCaseResponse decide(String reference, CaseDecisionRequest request) {
        String username = currentUserService.requireUsername();
        List<String> authorities = currentUserService.currentAuthorities();

        // Lock order is case first, then accounts (taken inside TransactionService), the same
        // order every other operation on a case uses, so two of them can never deadlock. Taking
        // the case lock first also makes a second, simultaneous decision wait and then find the
        // case already resolved.
        FraudCase fraudCase = rowLocker.lockCase(reference);
        requireOpenCase(fraudCase);

        Transaction transaction = fraudCase.getAlert().getTransaction();
        boolean approve = "APPROVE".equalsIgnoreCase(request.decision());
        String remarks = request.remarks();

        // "Not mine" outranks everything: the account holder says this is not their transfer, so
        // it may be blocked but never released. (A "this is mine" statement, by contrast, is only
        // evidence - the analyst still decides.)
        if (approve && "NOT_MINE".equals(fraudCase.getCustomerResponse())) {
            throw new BusinessRuleException(
                    HttpStatus.CONFLICT, "CUSTOMER_REPORTED_NOT_MINE",
                    "The account holder reported this transfer as not theirs, so it cannot be approved. "
                            + "Block it, or contact the customer and record why this report was wrong.");
        }

        if (transaction.isFinal()) {
            // A case can be opened for a transfer that is already final: three failed verification
            // codes block the transfer and escalate the alert (FR-14, FR-18). Such a case is a
            // review of an outcome that already happened. The analyst can confirm it to close the
            // case; the transfer itself is final and is never changed here (FR-22), so it cannot
            // be released by approving the case.
            if (approve) {
                throw new BusinessRuleException(
                        "INVALID_STATE",
                        "This transfer was already " + transaction.getStatus().name().toLowerCase()
                                + " and cannot be released. Confirm the outcome to close this case.");
            }
            fraudCase.setStatus(CaseStatus.RESOLVED_BLOCKED);
        } else if (approve) {
            Transaction settled =
                    transactionService.releaseAfterReview(transaction, remarks, username, authorities);
            // The case outcome is derived from what actually happened to the money, never from
            // what the analyst asked for: a release can end FAILED (the funds were no longer
            // there), and a case must not read "approved" over a transfer that did not happen.
            if (settled.getStatus() == TransactionStatus.APPROVED) {
                fraudCase.setStatus(CaseStatus.RESOLVED_APPROVED);
            } else {
                fraudCase.setStatus(CaseStatus.RESOLVED_BLOCKED);
                remarks = abbreviate("[Approval not applied: the transfer ended " + settled.getStatus()
                        + " - " + settled.getStatusReason() + "] " + remarks, 500);
            }
        } else {
            transactionService.blockAfterReview(transaction, remarks, username, authorities);
            fraudCase.setStatus(CaseStatus.RESOLVED_BLOCKED);
        }

        fraudCase.setRemarks(remarks);
        fraudCase.setDecidedBy(username);
        fraudCase.setClosedAt(Instant.now());
        if (fraudCase.getAssignedTo() == null) {
            fraudCase.setAssignedTo(username);
        }

        FraudAlert alert = fraudCase.getAlert();
        alert.setStatus(AlertStatus.CLOSED);
        fraudAlertRepository.save(alert);

        auditService.success(
                username, authorities, AuditAction.FRAUD_CASE_DECIDED, "FraudCase",
                fraudCase.getReference(),
                "Decision " + request.decision().toUpperCase() + " on transaction "
                        + transaction.getReference() + " (case " + fraudCase.getStatus() + ", transfer "
                        + transaction.getStatus() + "): " + request.remarks());

        return toResponse(fraudCaseRepository.save(fraudCase));
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private FraudCase requireCase(String reference) {
        return fraudCaseRepository
                .findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Fraud case", reference));
    }

    private void requireOpenCase(FraudCase fraudCase) {
        if (fraudCase.getStatus() == CaseStatus.RESOLVED_APPROVED
                || fraudCase.getStatus() == CaseStatus.RESOLVED_BLOCKED) {
            throw new BusinessRuleException(
                    "CASE_ALREADY_RESOLVED",
                    "This case was already resolved as " + fraudCase.getStatus() + ".");
        }
    }

    private FraudCaseResponse toResponse(FraudCase fraudCase) {
        FraudAlert alert = fraudCase.getAlert();
        Transaction transaction = alert.getTransaction();

        return new FraudCaseResponse(
                fraudCase.getId(),
                fraudCase.getReference(),
                alert.getReference(),
                transaction.getReference(),
                transaction.getSourceAccount().getCustomer().getFullName(),
                transaction.getAmount(),
                alert.getRiskLevel().name(),
                alert.getRiskScore(),
                transaction.getRiskReason(),
                transaction.getStatus().name(),
                fraudCase.getStatus().name(),
                fraudCase.getAssignedTo(),
                fraudCase.getRemarks(),
                fraudCase.getDecidedBy(),
                fraudCase.getCreatedAt(),
                fraudCase.getUpdatedAt(),
                fraudCase.getClosedAt(),
                fraudCase.getCustomerResponse(),
                fraudCase.getCustomerRespondedAt(),
                fraudCase.getReviewDueAt());
    }
}
