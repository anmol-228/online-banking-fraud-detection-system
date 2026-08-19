package com.sepro.obfds.dto;

import java.util.List;

/** The profile of the caller, used to restore a session after a page refresh. */
public record UserProfileResponse(
        String username,
        String fullName,
        String email,
        List<String> roles,
        String customerNumber,
        String phone,
        String address) {}
