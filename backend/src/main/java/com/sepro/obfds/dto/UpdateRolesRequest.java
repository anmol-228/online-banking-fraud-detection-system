package com.sepro.obfds.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * The full set of roles a user should hold after the change (FR-19).
 *
 * <p>The complete set is sent rather than a single role to add, so that the result of the request
 * does not depend on what the roles happened to be when the screen was loaded.</p>
 */
public record UpdateRolesRequest(
        @NotEmpty(message = "A user must keep at least one role") List<String> roles) {}
