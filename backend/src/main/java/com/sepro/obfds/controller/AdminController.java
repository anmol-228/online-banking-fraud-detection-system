package com.sepro.obfds.controller;

import com.sepro.obfds.dto.UpdateRolesRequest;
import com.sepro.obfds.dto.UpdateUserStatusRequest;
import com.sepro.obfds.dto.UserSummaryResponse;
import com.sepro.obfds.service.AdminService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User and role administration (FR-19). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserSummaryResponse>> users() {
        return ResponseEntity.ok(adminService.listUsers());
    }

    @GetMapping("/roles")
    public ResponseEntity<List<String>> roles() {
        return ResponseEntity.ok(adminService.listRoles());
    }

    /** FR-19: replace the set of roles held by a user. */
    @PutMapping("/users/{userId}/roles")
    public ResponseEntity<UserSummaryResponse> updateRoles(
            @PathVariable Long userId, @Valid @RequestBody UpdateRolesRequest request) {
        return ResponseEntity.ok(adminService.updateRoles(userId, request));
    }

    /** FR-19: enable or disable a login. */
    @PutMapping("/users/{userId}/status")
    public ResponseEntity<UserSummaryResponse> updateStatus(
            @PathVariable Long userId, @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok(adminService.updateStatus(userId, request));
    }
}
