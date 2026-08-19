package com.sepro.obfds.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** A dispute record (FR-17). */
public record DisputeResponse(
        Long id,
        String reference,
        String transactionReference,
        BigDecimal transactionAmount,
        String customerName,
        String subject,
        String description,
        String status,
        String resolution,
        String handledBy,
        Instant createdAt,
        Instant updatedAt) {}
