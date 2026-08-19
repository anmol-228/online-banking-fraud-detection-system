package com.sepro.obfds.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for every error the application raises deliberately.
 *
 * <p>Each instance carries the HTTP status to return, a short stable machine-readable code that
 * the front end can branch on, and a message that is safe to show to a user. Carrying the status
 * on the exception keeps the decision next to the business rule that made it, and keeps the
 * global handler small (NFR-07).</p>
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
