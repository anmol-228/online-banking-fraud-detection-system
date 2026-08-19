package com.sepro.obfds.dto;

import java.time.Instant;

/** A customer notification (FR-16). */
public record NotificationResponse(
        Long id,
        String type,
        String title,
        String message,
        String relatedReference,
        boolean read,
        Instant createdAt) {}
