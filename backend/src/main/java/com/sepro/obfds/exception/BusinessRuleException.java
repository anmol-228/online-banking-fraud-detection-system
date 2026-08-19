package com.sepro.obfds.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a request is well formed but breaks a banking rule, for example an insufficient
 * simulated balance or an invalid transaction state transition (FR-09, FR-22).
 */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public BusinessRuleException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
