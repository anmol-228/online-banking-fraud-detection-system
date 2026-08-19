package com.sepro.obfds.dto;

import java.time.Instant;

/**
 * Fraud-related figures for bank staff (FR-21).
 *
 * @param detectionRatePercent share of all transactions that raised an alert. This measures how
 *     often the rules fired, not how accurate they were.
 */
public record FraudReportResponse(
        Instant generatedAt,
        long lowRiskTransactions,
        long mediumRiskTransactions,
        long highRiskTransactions,
        long totalAlerts,
        long openAlerts,
        long closedAlerts,
        long totalCases,
        long openCases,
        long casesApproved,
        long casesBlocked,
        double detectionRatePercent) {}
