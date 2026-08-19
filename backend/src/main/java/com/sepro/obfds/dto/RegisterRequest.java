package com.sepro.obfds.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Customer self-registration payload (FR-01). */
public record RegisterRequest(
        @NotBlank(message = "Username is required")
                @Size(min = 4, max = 40, message = "Username must be between 4 and 40 characters")
                @Pattern(
                        regexp = "^[A-Za-z0-9._-]+$",
                        message = "Username may only contain letters, digits, dot, underscore and hyphen")
                String username,
        @NotBlank(message = "Password is required")
                @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
                String password,
        @NotBlank(message = "Full name is required") @Size(max = 120) String fullName,
        @NotBlank(message = "Email is required") @Email(message = "Enter a valid email address")
                @Size(max = 150)
                String email,
        @Size(max = 20) String phone,
        @Size(max = 250) String address) {}
