package com.sepro.obfds.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Chooses a pre-approved recipient by key. It never carries an address. */
public record EscalationRequest(
        @NotBlank(message = "Choose a recipient") @Size(max = 60, message = "Recipient key is too long")
                String recipientKey) {}
