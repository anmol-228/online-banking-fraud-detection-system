package com.sepro.obfds.dto;

import jakarta.validation.constraints.NotBlank;

/** Login payload (FR-02). */
public record LoginRequest(
        @NotBlank(message = "Username is required") String username,
        @NotBlank(message = "Password is required") String password) {}
