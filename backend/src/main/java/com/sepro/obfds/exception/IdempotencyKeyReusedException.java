package com.sepro.obfds.exception;

import org.springframework.http.HttpStatus;

/**
 * An idempotency key was presented that already belongs to a different request.
 *
 * <p>The message is fixed and generic on purpose. Whether the key belongs to another customer or to
 * a different payload of the caller's own, the caller learns only that the key cannot be used - never
 * who owns it, what it paid, or whether it exists at all.</p>
 */
public class IdempotencyKeyReusedException extends ApiException {

    public IdempotencyKeyReusedException() {
        super(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "This request key has already been used for a different request. "
                        + "Start a new transfer to get a new key.");
    }
}
