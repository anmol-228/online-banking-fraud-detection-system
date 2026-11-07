package com.sepro.obfds.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login credentials.
 *
 * <p>The length limits are a security control, not just tidiness: they are checked before
 * authentication, so an oversized value is rejected without ever reaching the password hash or
 * the audit log (SEC-4). The username limit matches the column it is compared with.</p>
 */
public record LoginRequest(
        @NotBlank(message = "Username is required")
                @Size(max = 60, message = "Username is too long")
                String username,
        @NotBlank(message = "Password is required")
                @Size(max = 128, message = "Password is too long")
                String password) {}
