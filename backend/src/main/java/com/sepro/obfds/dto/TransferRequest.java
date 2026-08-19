package com.sepro.obfds.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Fund transfer payload (FR-07, FR-09).
 *
 * @param idempotencyKey a value generated once per transfer form submission. If the same key
 *     arrives twice the first transaction is returned instead of creating a second one (TC-14).
 */
public record TransferRequest(
        @NotBlank(message = "Source account is required")
                @Pattern(regexp = "^[0-9]{9,18}$", message = "Enter a valid source account number")
                String sourceAccountNumber,
        @NotBlank(message = "Destination account is required")
                @Pattern(regexp = "^[0-9]{9,18}$", message = "Enter a valid destination account number")
                String destinationAccountNumber,
        @NotNull(message = "Amount is required")
                @DecimalMin(value = "1.00", message = "Amount must be at least 1.00")
                @DecimalMax(value = "1000000.00", message = "Amount may not exceed 1000000.00")
                @Digits(integer = 13, fraction = 2, message = "Amount may have at most two decimal places")
                BigDecimal amount,
        @Size(max = 200) String description,
        @Size(max = 80) String idempotencyKey) {}
