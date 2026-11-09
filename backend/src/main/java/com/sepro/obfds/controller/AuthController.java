package com.sepro.obfds.controller;

import com.sepro.obfds.dto.AuthResponse;
import com.sepro.obfds.dto.LoginRequest;
import com.sepro.obfds.dto.RegisterRequest;
import com.sepro.obfds.dto.UserProfileResponse;
import com.sepro.obfds.security.AuthThrottle;
import com.sepro.obfds.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Registration, login and profile endpoints (FR-01, FR-02, FR-03). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthThrottle throttle;

    public AuthController(AuthService authService, AuthThrottle throttle) {
        this.authService = authService;
        this.throttle = throttle;
    }

    /** FR-01: register a new online banking profile. */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    /** FR-02: log in with valid credentials. */
    /**
     * Signs a user in.
     *
     * <p>The throttle is consulted <em>before</em> the password is checked, so a locked client
     * costs the server almost nothing and cannot confirm a guess even if it is right (SEC-4).
     * Bean Validation has already run by this point, which is what rejects an oversized value
     * without a hash being computed.</p>
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String address = http.getRemoteAddr();
        throttle.checkAllowed(request.username(), address);
        try {
            AuthResponse response = authService.login(request);
            throttle.recordSuccess(request.username(), address);
            return ResponseEntity.ok(response);
        } catch (AuthenticationException ex) {
            throttle.recordFailure(request.username(), address);
            throw ex;
        }
    }

    /** FR-03: the profile of the authenticated caller. */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me() {
        return ResponseEntity.ok(authService.currentProfile());
    }
}
