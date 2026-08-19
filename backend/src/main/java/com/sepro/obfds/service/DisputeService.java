package com.sepro.obfds.service;

import com.sepro.obfds.audit.AuditAction;
import com.sepro.obfds.audit.AuditService;
import com.sepro.obfds.dto.DisputeRequest;
import com.sepro.obfds.dto.DisputeResolutionRequest;
import com.sepro.obfds.dto.DisputeResponse;
import com.sepro.obfds.entity.Customer;
import com.sepro.obfds.entity.Dispute;
import com.sepro.obfds.entity.DisputeStatus;
import com.sepro.obfds.entity.NotificationType;
import com.sepro.obfds.entity.Transaction;
import com.sepro.obfds.exception.BusinessRuleException;
import com.sepro.obfds.exception.ResourceNotFoundException;
import com.sepro.obfds.notification.NotificationService;
import com.sepro.obfds.repository.DisputeRepository;
import com.sepro.obfds.repository.TransactionRepository;
import com.sepro.obfds.security.CurrentUserService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Complaint and dispute handling (FR-17).
 *
 * <p>A customer raises a dispute against one of their own transactions. An operations officer or
 * an administrator then works the queue and records an outcome, which notifies the customer.</p>
 */
@Service
public class DisputeService {

    private final DisputeRepository disputeRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationService notificationService;
    private final CurrentUserService currentUserService;
    private final ReferenceGenerator referenceGenerator;
    private final AuditService auditService;

    public DisputeService(
            DisputeRepository disputeRepository,
            TransactionRepository transactionRepository,
            NotificationService notificationService,
            CurrentUserService currentUserService,
            ReferenceGenerator referenceGenerator,
            AuditService auditService) {
        this.disputeRepository = disputeRepository;
        this.transactionRepository = transactionRepository;
        this.notificationService = notificationService;
        this.currentUserService = currentUserService;
        this.referenceGenerator = referenceGenerator;
        this.auditService = auditService;
    }

    /** A customer raises a complaint about one of their transactions (FR-17). */
    @Transactional
    public DisputeResponse submit(DisputeRequest request) {
        Customer customer = currentUserService.requireCustomer();
        String username = currentUserService.requireUsername();

        Transaction transaction = transactionRepository
                .findByReference(request.transactionReference().trim())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Transaction", request.transactionReference()));

        if (!transaction.getSourceAccount().getCustomer().getId().equals(customer.getId())) {
            // Same reasoning as elsewhere: do not confirm that another customer reference exists.
            throw new ResourceNotFoundException("Transaction", request.transactionReference());
        }

        boolean alreadyOpen = disputeRepository.existsByTransactionIdAndStatusIn(
                transaction.getId(), List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW));
        if (alreadyOpen) {
            throw new BusinessRuleException(
                    HttpStatus.CONFLICT,
                    "DISPUTE_ALREADY_OPEN",
                    "There is already an open dispute for this transaction.");
        }

        Dispute dispute = new Dispute();
        dispute.setReference(referenceGenerator.disputeReference());
        dispute.setCustomer(customer);
        dispute.setTransaction(transaction);
        dispute.setSubject(request.subject().trim());
        dispute.setDescription(request.description().trim());
        dispute.setStatus(DisputeStatus.OPEN);
        Dispute saved = disputeRepository.save(dispute);

        auditService.success(
                username,
                currentUserService.currentAuthorities(),
                AuditAction.DISPUTE_SUBMITTED,
                "Dispute",
                saved.getReference(),
                "Dispute raised against transaction " + transaction.getReference());

        notificationService.notify(
                customer,
                NotificationType.DISPUTE,
                "Complaint received",
                "We have received your complaint " + saved.getReference() + " about transaction "
                        + transaction.getReference() + ". Our team will review it and update you.",
                saved.getReference());

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<DisputeResponse> myDisputes() {
        Customer customer = currentUserService.requireCustomer();
        return disputeRepository.findByCustomerIdOrderByCreatedAtDesc(customer.getId()).stream()
                .map(DisputeService::toResponse)
                .toList();
    }

    /** The staff work queue (FR-17). */
    @Transactional(readOnly = true)
    public List<DisputeResponse> queue() {
        return disputeRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(DisputeService::toResponse)
                .toList();
    }

    /** An operations officer records progress or an outcome on a dispute (FR-17). */
    @Transactional
    public DisputeResponse resolve(String reference, DisputeResolutionRequest request) {
        String username = currentUserService.requireUsername();

        Dispute dispute = disputeRepository
                .findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute", reference));

        if (dispute.getStatus() == DisputeStatus.RESOLVED
                || dispute.getStatus() == DisputeStatus.REJECTED) {
            throw new BusinessRuleException(
                    "DISPUTE_ALREADY_CLOSED",
                    "This dispute was already closed as " + dispute.getStatus() + ".");
        }

        DisputeStatus newStatus = DisputeStatus.valueOf(request.status().toUpperCase());
        dispute.setStatus(newStatus);
        dispute.setResolution(request.resolution().trim());
        dispute.setHandledBy(username);
        Dispute saved = disputeRepository.save(dispute);

        auditService.success(
                username,
                currentUserService.currentAuthorities(),
                AuditAction.DISPUTE_RESOLVED,
                "Dispute",
                saved.getReference(),
                "Dispute set to " + newStatus + ": " + request.resolution());

        notificationService.notify(
                dispute.getCustomer(),
                NotificationType.DISPUTE,
                "Update on complaint " + saved.getReference(),
                "Your complaint " + saved.getReference() + " is now " + newStatus + ". "
                        + request.resolution(),
                saved.getReference());

        return toResponse(saved);
    }

    public static DisputeResponse toResponse(Dispute dispute) {
        return new DisputeResponse(
                dispute.getId(),
                dispute.getReference(),
                dispute.getTransaction().getReference(),
                dispute.getTransaction().getAmount(),
                dispute.getCustomer().getFullName(),
                dispute.getSubject(),
                dispute.getDescription(),
                dispute.getStatus().name(),
                dispute.getResolution(),
                dispute.getHandledBy(),
                dispute.getCreatedAt(),
                dispute.getUpdatedAt());
    }
}
