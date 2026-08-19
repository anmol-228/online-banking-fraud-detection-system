package com.sepro.obfds.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** A fraud alert as shown on the analyst dashboard (FR-13, FR-18). */
public record FraudAlertResponse(
        Long id,
        String reference,
        String transactionReference,
        String customerName,
        String customerNumber,
        String sourceAccountNumber,
        String destinationAccountNumber,
        BigDecimal amount,
        String riskLevel,
        int riskScore,
        String reason,
        String status,
        String transactionStatus,
        String caseReference,
        Instant createdAt) {}
