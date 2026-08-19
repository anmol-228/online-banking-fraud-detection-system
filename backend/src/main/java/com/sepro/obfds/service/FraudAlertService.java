package com.sepro.obfds.service;

import com.sepro.obfds.dto.FraudAlertResponse;
import com.sepro.obfds.entity.AlertStatus;
import com.sepro.obfds.entity.FraudAlert;
import com.sepro.obfds.entity.Transaction;
import com.sepro.obfds.exception.ResourceNotFoundException;
import com.sepro.obfds.repository.FraudAlertRepository;
import com.sepro.obfds.repository.FraudCaseRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the alert module, used by the fraud analyst dashboard (FR-13, FR-18).
 *
 * <p>Alerts are created by the transaction workflow, never here. This service only reads them,
 * which keeps the rule that an alert is always the consequence of a risk decision.</p>
 */
@Service
public class FraudAlertService {

    private final FraudAlertRepository fraudAlertRepository;
    private final FraudCaseRepository fraudCaseRepository;

    public FraudAlertService(
            FraudAlertRepository fraudAlertRepository, FraudCaseRepository fraudCaseRepository) {
        this.fraudAlertRepository = fraudAlertRepository;
        this.fraudCaseRepository = fraudCaseRepository;
    }

    /** All alerts, or only those in one status when a filter is supplied. */
    @Transactional(readOnly = true)
    public List<FraudAlertResponse> listAlerts(String statusFilter) {
        List<FraudAlert> alerts;
        if (statusFilter == null || statusFilter.isBlank()) {
            alerts = fraudAlertRepository.findAllByOrderByCreatedAtDesc();
        } else {
            AlertStatus status = parseStatus(statusFilter);
            alerts = fraudAlertRepository.findByStatusOrderByCreatedAtDesc(status);
        }
        return alerts.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public FraudAlertResponse getAlert(String reference) {
        FraudAlert alert = fraudAlertRepository
                .findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Fraud alert", reference));
        return toResponse(alert);
    }

    private AlertStatus parseStatus(String value) {
        try {
            return AlertStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResourceNotFoundException("Alert status", value);
        }
    }

    private FraudAlertResponse toResponse(FraudAlert alert) {
        Transaction transaction = alert.getTransaction();
        String caseReference = fraudCaseRepository
                .findByAlertId(alert.getId())
                .map(fraudCase -> fraudCase.getReference())
                .orElse(null);

        return new FraudAlertResponse(
                alert.getId(),
                alert.getReference(),
                transaction.getReference(),
                transaction.getSourceAccount().getCustomer().getFullName(),
                transaction.getSourceAccount().getCustomer().getCustomerNumber(),
                transaction.getSourceAccount().getAccountNumber(),
                transaction.getDestinationAccountNumber(),
                transaction.getAmount(),
                alert.getRiskLevel().name(),
                alert.getRiskScore(),
                alert.getReason(),
                alert.getStatus().name(),
                transaction.getStatus().name(),
                caseReference,
                alert.getCreatedAt());
    }
}
