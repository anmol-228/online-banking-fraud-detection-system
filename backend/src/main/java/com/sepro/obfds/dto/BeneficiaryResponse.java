package com.sepro.obfds.dto;

import java.time.Instant;

/** A saved payee (FR-08). */
public record BeneficiaryResponse(
        Long id,
        String name,
        String accountNumber,
        String bankName,
        String ifscCode,
        String nickname,
        boolean active,
        Instant createdAt) {}
