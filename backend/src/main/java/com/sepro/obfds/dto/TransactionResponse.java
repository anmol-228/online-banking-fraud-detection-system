package com.sepro.obfds.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A transaction as shown to a customer or to bank staff (FR-10, FR-22).
 *
 * @param status one of PENDING, PENDING_VERIFICATION, APPROVED, BLOCKED, FAILED
 * @param riskLevel LOW, MEDIUM or HIGH (FR-12)
 * @param verificationRequired true while the customer still has to enter a verification code
 */
public record TransactionResponse(
        Long id,
        String reference,
        String sourceAccountNumber,
        String destinationAccountNumber,
        String destinationName,
        BigDecimal amount,
        String currency,
        String description,
        String status,
        String statusReason,
        String riskLevel,
        int riskScore,
        String riskReason,
        boolean verificationRequired,
        String initiatedBy,
        Instant createdAt,
        Instant completedAt) {}
