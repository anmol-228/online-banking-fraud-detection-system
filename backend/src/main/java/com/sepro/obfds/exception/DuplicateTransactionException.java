package com.sepro.obfds.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when the same transfer appears to have been submitted twice in quick succession
 * (TC-14, NFR-09).
 */
public class DuplicateTransactionException extends ApiException {

    public DuplicateTransactionException(String existingReference) {
        super(
                HttpStatus.CONFLICT,
                "DUPLICATE_TRANSACTION",
                "An identical transfer was submitted moments ago (reference "
                        + existingReference
                        + "). Please check your transaction history before trying again.");
    }
}
