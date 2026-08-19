package com.sepro.obfds.dto;

import java.util.List;

/**
 * Issued after a successful login (FR-02).
 *
 * @param token the signed JWT the client sends on every later request
 * @param expiresInSeconds remaining lifetime of the token
 * @param roles role names without the Spring Security prefix, used by the front end to decide
 *     which navigation items to display
 */
public record AuthResponse(
        String token,
        long expiresInSeconds,
        String username,
        String fullName,
        List<String> roles,
        String customerNumber) {}
