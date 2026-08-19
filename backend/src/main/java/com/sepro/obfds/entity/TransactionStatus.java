package com.sepro.obfds.entity;

/**
 * Authoritative transaction status model (FR-22).
 *
 * <p>PENDING is used when a HIGH risk transaction is held for fraud review.
 * PENDING_VERIFICATION is used when a MEDIUM risk transaction is waiting for the
 * customer to complete additional verification (FR-14).</p>
 */
public enum TransactionStatus {
    PENDING,
    PENDING_VERIFICATION,
    APPROVED,
    BLOCKED,
    FAILED
}
