package com.sepro.obfds.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Account details shown to the owning customer (FR-05).
 *
 * @param availableBalance balance minus the amount reserved by transfers that are still pending
 */
public record AccountResponse(
        Long id,
        String accountNumber,
        String accountType,
        BigDecimal balance,
        BigDecimal availableBalance,
        String currency,
        String status,
        Instant openedAt) {}
