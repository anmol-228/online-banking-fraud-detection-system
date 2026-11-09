package com.sepro.obfds.exception;

import jakarta.servlet.http.HttpServletRequest;
import com.sepro.obfds.security.TooManyAttemptsException;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central error handling for the whole API (NFR-07).
 *
 * <p>Two rules apply throughout. Anything the caller can act on gets a specific message. Anything
 * caused by an internal fault gets a generic message, while the real detail goes to the server
 * log only, so that internal structure is never leaked to a browser (NFR-01).</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Every error the application raises deliberately. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(
                        ex.getStatus().value(), ex.getCode(), ex.getMessage(), request.getRequestURI()));
    }

    /** Bean Validation failures on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiError.validation(request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations()
                .forEach(violation ->
                        fieldErrors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.validation(request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleMalformedRequest(Exception ex, HttpServletRequest request) {
        log.debug("Malformed request to {}", request.getRequestURI(), ex);
        return ResponseEntity.badRequest()
                .body(ApiError.of(
                        400,
                        "MALFORMED_REQUEST",
                        "The request could not be read. Please check the values you submitted.",
                        request.getRequestURI()));
    }

    /**
     * A request for an address that does not exist.
     *
     * <p>Without this the catch-all handler below would turn an ordinary wrong URL into a 500,
     * which would make a simple typo look like a server fault.</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(
                        404, "NOT_FOUND", "The requested address does not exist.", request.getRequestURI()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(HttpServletRequest request) {
        // Deliberately does not say whether it was the username or the password that was wrong.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(
                        401, "INVALID_CREDENTIALS", "Invalid username or password.", request.getRequestURI()));
    }

    /**
     * A disabled login is reported exactly like a wrong password. Saying "this account is
     * disabled" would tell an attacker which usernames exist, and gains the genuine user nothing
     * they could not learn by asking the administrator (SEC-1).
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthenticationFailure(HttpServletRequest request) {
        return handleBadCredentials(request);
    }

    /** A login attempt refused by the throttle; tells the client how long to wait (SEC-4). */
    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ApiError> handleTooManyAttempts(
            TooManyAttemptsException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiError.of(429, ex.getCode(), ex.getMessage(), request.getRequestURI()));
    }

    /**
     * The database refused a row lock, hit a deadlock or timed out waiting for a lock.
     *
     * <p>Money-moving code takes its locks in a fixed order, so this is a backstop and not an
     * expected path. It is still answered deterministically (409, safe to retry) rather than as a
     * server fault, because nothing was applied: the whole transaction rolled back.</p>
     */
    @ExceptionHandler({
        PessimisticLockingFailureException.class,
        jakarta.persistence.PessimisticLockException.class,
        jakarta.persistence.LockTimeoutException.class
    })
    public ResponseEntity<ApiError> handlePessimisticLock(Exception ex, HttpServletRequest request) {
        log.warn("Row lock refused on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        409,
                        "CONCURRENT_UPDATE",
                        "Another operation was working on this account at the same time. "
                                + "Nothing was changed; please try again.",
                        request.getRequestURI()));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(
                        403,
                        "FORBIDDEN",
                        "Your role is not permitted to perform this operation.",
                        request.getRequestURI()));
    }

    /** Two users tried to change the same account at the same moment (NFR-09). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(
            OptimisticLockingFailureException ex, HttpServletRequest request) {

        log.warn("Concurrent update rejected on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        409,
                        "CONCURRENT_UPDATE",
                        "Another operation updated this account at the same time. Please try again.",
                        request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {

        log.warn("Data integrity violation on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        409,
                        "DATA_INTEGRITY_VIOLATION",
                        "This operation conflicts with data that already exists.",
                        request.getRequestURI()));
    }

    /** Database or persistence failure (TC-19). */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDataAccess(
            DataAccessException ex, HttpServletRequest request) {

        log.error("Database failure on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(
                        503,
                        "SERVICE_UNAVAILABLE",
                        "The service is temporarily unable to process this request. Please try again shortly.",
                        request.getRequestURI()));
    }

    /** Anything not handled above. The detail stays in the log, not in the response. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected failure on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(
                        500,
                        "INTERNAL_ERROR",
                        "Something went wrong while processing your request.",
                        request.getRequestURI()));
    }
}
