package com.sepro.obfds.entity;

/** Lifecycle of a fraud case handled by a fraud analyst (FR-18). */
public enum CaseStatus {
    OPEN,
    UNDER_REVIEW,
    RESOLVED_APPROVED,
    RESOLVED_BLOCKED
}
