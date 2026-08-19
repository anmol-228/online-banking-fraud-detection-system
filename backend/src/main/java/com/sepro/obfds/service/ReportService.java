package com.sepro.obfds.service;

import com.sepro.obfds.audit.AuditAction;
import com.sepro.obfds.audit.AuditService;
import com.sepro.obfds.dto.FraudReportResponse;
import com.sepro.obfds.dto.OperationalReportResponse;
import com.sepro.obfds.entity.AlertStatus;
import com.sepro.obfds.entity.CaseStatus;
import com.sepro.obfds.entity.DisputeStatus;
import com.sepro.obfds.entity.RiskLevel;
import com.sepro.obfds.entity.TransactionStatus;
import com.sepro.obfds.repository.AccountRepository;
import com.sepro.obfds.repository.CustomerRepository;
import com.sepro.obfds.repository.DisputeRepository;
import com.sepro.obfds.repository.FraudAlertRepository;
import com.sepro.obfds.repository.FraudCaseRepository;
import com.sepro.obfds.repository.TransactionRepository;
import com.sepro.obfds.security.CurrentUserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Basic operational and fraud reporting for bank staff (FR-21).
 *
 * <p>Every figure is a direct count or sum over the stored data. Nothing is estimated, so a
 * report can always be checked against the transaction list on screen.</p>
 */
@Service
public class ReportService {

    private final TransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final FraudCaseRepository fraudCaseRepository;
    private final DisputeRepository disputeRepository;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public ReportService(
            TransactionRepository transactionRepository,
            CustomerRepository customerRepository,
            AccountRepository accountRepository,
            FraudAlertRepository fraudAlertRepository,
            FraudCaseRepository fraudCaseRepository,
            DisputeRepository disputeRepository,
            CurrentUserService currentUserService,
            AuditService auditService) {
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.fraudCaseRepository = fraudCaseRepository;
        this.disputeRepository = disputeRepository;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    /** Operational summary: volumes, statuses and money moved (FR-21). */
    @Transactional(readOnly = true)
    public OperationalReportResponse operationalReport() {
        BigDecimal approvedAmount = transactionRepository.sumAmountByStatus(TransactionStatus.APPROVED);

        OperationalReportResponse report = new OperationalReportResponse(
                Instant.now(),
                customerRepository.count(),
                accountRepository.count(),
                transactionRepository.count(),
                transactionRepository.countByStatus(TransactionStatus.APPROVED),
                transactionRepository.countByStatus(TransactionStatus.PENDING),
                transactionRepository.countByStatus(TransactionStatus.PENDING_VERIFICATION),
                transactionRepository.countByStatus(TransactionStatus.BLOCKED),
                transactionRepository.countByStatus(TransactionStatus.FAILED),
                approvedAmount == null ? BigDecimal.ZERO : approvedAmount,
                disputeRepository.countByStatus(DisputeStatus.OPEN));

        auditReportAccess("OPERATIONAL");
        return report;
    }

    /** Fraud summary: risk mix, alerts and case outcomes (FR-21). */
    @Transactional(readOnly = true)
    public FraudReportResponse fraudReport() {
        long totalTransactions = transactionRepository.count();
        long totalAlerts = fraudAlertRepository.count();

        double detectionRate = totalTransactions == 0
                ? 0.0
                : BigDecimal.valueOf(totalAlerts)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalTransactions), 2, RoundingMode.HALF_UP)
                        .doubleValue();

        FraudReportResponse report = new FraudReportResponse(
                Instant.now(),
                transactionRepository.countByRiskLevel(RiskLevel.LOW),
                transactionRepository.countByRiskLevel(RiskLevel.MEDIUM),
                transactionRepository.countByRiskLevel(RiskLevel.HIGH),
                totalAlerts,
                fraudAlertRepository.countByStatus(AlertStatus.OPEN),
                fraudAlertRepository.countByStatus(AlertStatus.CLOSED),
                fraudCaseRepository.count(),
                fraudCaseRepository.countByStatus(CaseStatus.OPEN),
                fraudCaseRepository.countByStatus(CaseStatus.RESOLVED_APPROVED),
                fraudCaseRepository.countByStatus(CaseStatus.RESOLVED_BLOCKED),
                detectionRate);

        auditReportAccess("FRAUD");
        return report;
    }

    /** Who generated which report is itself an audited event (FR-20). */
    private void auditReportAccess(String reportType) {
        auditService.success(
                currentUserService.requireUsername(),
                currentUserService.currentAuthorities(),
                AuditAction.REPORT_GENERATED,
                "Report",
                reportType,
                reportType + " report generated");
    }
}
