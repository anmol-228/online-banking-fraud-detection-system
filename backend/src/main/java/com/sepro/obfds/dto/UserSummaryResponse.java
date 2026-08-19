package com.sepro.obfds.dto;

import java.time.Instant;
import java.util.List;

/** A user row on the administrator user-management screen (FR-19). */
public record UserSummaryResponse(
        Long id,
        String username,
        String fullName,
        String email,
        List<String> roles,
        boolean enabled,
        String customerNumber,
        Instant createdAt) {}
