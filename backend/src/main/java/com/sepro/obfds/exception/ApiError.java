package com.sepro.obfds.exception;

import java.time.Instant;
import java.util.Map;

/**
 * The single error shape returned by every endpoint.
 *
 * @param timestamp when the error was produced
 * @param status HTTP status code
 * @param code short stable machine-readable code, for example INSUFFICIENT_BALANCE
 * @param message a message that is safe to display to a user
 * @param path the request path
 * @param fieldErrors per-field validation messages, empty when the failure was not a validation
 *     failure
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(Instant.now(), status, code, message, path, Map.of());
    }

    public static ApiError validation(String path, Map<String, String> fieldErrors) {
        return new ApiError(
                Instant.now(),
                400,
                "VALIDATION_FAILED",
                "Some of the information you entered is not valid.",
                path,
                fieldErrors);
    }
}
