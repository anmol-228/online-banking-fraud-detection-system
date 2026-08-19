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
import com.sepro.obfds.exception.BusinessRuleException;
import com.sepro.obfds.exception.ResourceNotFoundException;
import com.sepro.obfds.repository.FraudAlertRepository;
import com.sepro.obfds.repository.FraudCaseRepository;
import com.sepro.obfds.security.CurrentUserService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
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

    public FraudCaseService(
            FraudCaseRepository fraudCaseRepository,
            FraudAlertRepository fraudAlertRepository,
            TransactionService transactionService,
            CurrentUserService currentUserService,
            AuditService auditService) {
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
    @Transactional
    public FraudCaseResponse assignToMe(String reference) {
        FraudCase fraudCase = requireCase(reference);
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
    @Transactional
    public FraudCaseResponse decide(String reference, CaseDecisionRequest request) {
        String username = currentUserService.requireUsername();
        List<String> authorities = currentUserService.currentAuthorities();

        FraudCase fraudCase = requireCase(reference);
        requireOpenCase(fraudCase);

        Transaction transaction = fraudCase.getAlert().getTransaction();
        boolean approve = "APPROVE".equalsIgnoreCase(request.decision());

        if (approve) {
            transactionService.releaseAfterReview(transaction, request.remarks(), username, authorities);
            fraudCase.setStatus(CaseStatus.RESOLVED_APPROVED);
        } else {
            transactionService.blockAfterReview(transaction, request.remarks(), username, authorities);
            fraudCase.setStatus(CaseStatus.RESOLVED_BLOCKED);
        }

        fraudCase.setRemarks(request.remarks());
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
                        + transaction.getReference() + ": " + request.remarks());

        return toResponse(fraudCaseRepository.save(fraudCase));
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
                fraudCase.getClosedAt());
    }
}
