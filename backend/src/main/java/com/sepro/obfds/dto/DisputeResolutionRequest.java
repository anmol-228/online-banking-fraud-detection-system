package com.sepro.obfds.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The outcome an operations officer records on a dispute (FR-17). */
public record DisputeResolutionRequest(
        @NotBlank(message = "Status is required")
                @Pattern(
                        regexp = "^(UNDER_REVIEW|RESOLVED|REJECTED)$",
                        message = "Status must be UNDER_REVIEW, RESOLVED or REJECTED")
                String status,
        @NotBlank(message = "Resolution notes are required") @Size(max = 1000) String resolution) {}
