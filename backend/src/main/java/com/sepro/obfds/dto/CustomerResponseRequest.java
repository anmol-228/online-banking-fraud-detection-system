package com.sepro.obfds.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * What the account holder says about a held high-risk transfer. Evidence for the analyst only.
 */
public record CustomerResponseRequest(
        @NotBlank(message = "Say whether this transfer is yours")
                @Pattern(regexp = "^(CONFIRMED_MINE|NOT_MINE)$", message = "Use CONFIRMED_MINE or NOT_MINE")
                String response) {}
