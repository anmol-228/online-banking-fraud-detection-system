package com.sepro.obfds.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Verification code submitted by the customer for a held transaction (FR-14). */
public record VerificationRequestDto(
        @NotBlank(message = "Verification code is required")
                @Pattern(regexp = "^[0-9]{6}$", message = "The verification code is six digits")
                String code) {}
