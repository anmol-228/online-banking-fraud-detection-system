package com.sepro.obfds.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** A fraud case as shown to a fraud analyst (FR-15, FR-18). */
public record FraudCaseResponse(
        Long id,
        String reference,
        String alertReference,
        String transactionReference,
        String customerName,
        BigDecimal amount,
        String riskLevel,
        int riskScore,
        String riskReason,
        String transactionStatus,
        String status,
        String assignedTo,
        String remarks,
        String decidedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt) {}
