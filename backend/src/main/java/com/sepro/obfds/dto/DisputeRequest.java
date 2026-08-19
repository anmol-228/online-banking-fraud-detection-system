package com.sepro.obfds.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A complaint or dispute raised by a customer about a transaction (FR-17). */
public record DisputeRequest(
        @NotBlank(message = "Transaction reference is required") @Size(max = 30)
                String transactionReference,
        @NotBlank(message = "Subject is required") @Size(max = 150) String subject,
        @NotBlank(message = "Please describe the problem")
                @Size(min = 10, max = 1000, message = "Description must be between 10 and 1000 characters")
                String description) {}
