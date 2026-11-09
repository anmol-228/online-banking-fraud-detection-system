package com.sepro.obfds.security;

import com.sepro.obfds.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Raised when a login attempt is refused by {@link AuthThrottle}; carries the wait in seconds. */
public class TooManyAttemptsException extends ApiException {

    private final long retryAfterSeconds;

    public TooManyAttemptsException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                "Too many sign-in attempts. Please wait " + retryAfterSeconds + " seconds and try again.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
