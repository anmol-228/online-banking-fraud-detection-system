package com.sepro.obfds.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a requested record does not exist, or exists but does not belong to the caller.
 *
 * <p>The same exception is used for both situations on purpose. If a customer asked for an
 * account belonging to somebody else, answering "not found" rather than "forbidden" avoids
 * confirming that the other account exists (NFR-08).</p>
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String resource) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", resource + " was not found.");
    }

    public ResourceNotFoundException(String resource, String identifier) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", resource + " " + identifier + " was not found.");
    }
}
