package com.sepro.obfds.dto;

import jakarta.validation.constraints.NotNull;

/** Enables or disables a login (FR-19). */
public record UpdateUserStatusRequest(
        @NotNull(message = "Enabled flag is required") Boolean enabled) {}
