package com.sepro.obfds.dto;

import java.time.Instant;

/** An audit trail row (FR-20). */
public record AuditLogResponse(
        Long id,
        Instant occurredAt,
        String username,
        String roles,
        String action,
        String entityType,
        String entityReference,
        String details,
        String outcome) {}
