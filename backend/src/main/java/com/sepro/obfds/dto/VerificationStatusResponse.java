package com.sepro.obfds.dto;

import java.time.Instant;

/** State of the outstanding verification challenge for a transaction (FR-14). */
public record VerificationStatusResponse(
        String transactionReference,
        String status,
        int attempts,
        int maxAttempts,
        int attemptsRemaining,
        Instant expiresAt) {}
