package com.sepro.obfds.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Balance enquiry result (FR-06). */
public record BalanceResponse(
        String accountNumber,
        BigDecimal balance,
        BigDecimal availableBalance,
        BigDecimal reservedAmount,
        String currency,
        Instant asOf) {}
