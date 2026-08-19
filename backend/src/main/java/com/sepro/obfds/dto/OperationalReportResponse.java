package com.sepro.obfds.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Basic operational figures for bank staff (FR-21). */
public record OperationalReportResponse(
        Instant generatedAt,
        long totalCustomers,
        long totalAccounts,
        long totalTransactions,
        long approvedTransactions,
        long pendingTransactions,
        long pendingVerificationTransactions,
        long blockedTransactions,
        long failedTransactions,
        BigDecimal totalApprovedAmount,
        long openDisputes) {}
