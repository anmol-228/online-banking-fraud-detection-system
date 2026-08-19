package com.sepro.obfds.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The decision a fraud analyst records on a case (FR-15).
 *
 * @param decision APPROVE releases the held transfer, BLOCK stops it permanently
 */
public record CaseDecisionRequest(
        @NotBlank(message = "Decision is required")
                @Pattern(
                        regexp = "^(APPROVE|BLOCK)$",
                        message = "Decision must be either APPROVE or BLOCK")
                String decision,
        @NotBlank(message = "Remarks are required so the decision can be audited")
                @Size(max = 500)
                String remarks) {}
