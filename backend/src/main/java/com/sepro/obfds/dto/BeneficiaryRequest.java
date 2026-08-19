package com.sepro.obfds.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Payload used to add a payee (FR-08). */
public record BeneficiaryRequest(
        @NotBlank(message = "Beneficiary name is required") @Size(max = 120) String name,
        @NotBlank(message = "Account number is required")
                @Pattern(
                        regexp = "^[0-9]{9,18}$",
                        message = "Account number must be between 9 and 18 digits")
                String accountNumber,
        @NotBlank(message = "Bank name is required") @Size(max = 120) String bankName,
        @Pattern(
                        regexp = "^$|^[A-Z]{4}0[A-Z0-9]{6}$",
                        message = "IFSC code must look like ABCD0123456")
                String ifscCode,
        @Size(max = 60) String nickname) {}
