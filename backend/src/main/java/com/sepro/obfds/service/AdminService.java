package com.sepro.obfds.service;

import com.sepro.obfds.audit.AuditAction;
import com.sepro.obfds.audit.AuditService;
import com.sepro.obfds.dto.UpdateRolesRequest;
import com.sepro.obfds.dto.UpdateUserStatusRequest;
import com.sepro.obfds.dto.UserSummaryResponse;
import com.sepro.obfds.entity.ApplicationUser;
import com.sepro.obfds.entity.Customer;
import com.sepro.obfds.entity.RoleName;
import com.sepro.obfds.entity.UserRole;
import com.sepro.obfds.exception.BusinessRuleException;
import com.sepro.obfds.exception.ResourceNotFoundException;
import com.sepro.obfds.repository.ApplicationUserRepository;
import com.sepro.obfds.repository.CustomerRepository;
import com.sepro.obfds.repository.UserRoleRepository;
import com.sepro.obfds.security.CurrentUserService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The administration module (FR-19).
 *
 * <p>Two guard rails apply to every change here. An administrator cannot remove their own
 * administrator role, and an administrator cannot disable their own login. Without them a single
 * mistake could leave the system with nobody able to administer it.</p>
 */
@Service
public class AdminService {

    private final ApplicationUserRepository userRepository;
    private final UserRoleRepository roleRepository;
    private final CustomerRepository customerRepository;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public AdminService(
            ApplicationUserRepository userRepository,
            UserRoleRepository roleRepository,
            CustomerRepository customerRepository,
            CurrentUserService currentUserService,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.customerRepository = customerRepository;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<UserSummaryResponse> listUsers() {
        return userRepository.findAll().stream().map(this::toResponse).toList();
    }

    /** All role names the administrator can choose from. */
    @Transactional(readOnly = true)
    public List<String> listRoles() {
        return roleRepository.findAll().stream().map(role -> role.getName().name()).sorted().toList();
    }

    /** Replaces the roles held by a user with the supplied set (FR-19). */
    @Transactional
    public UserSummaryResponse updateRoles(Long userId, UpdateRolesRequest request) {
        String actingUsername = currentUserService.requireUsername();
        ApplicationUser user = requireUser(userId);

        Set<UserRole> newRoles = new LinkedHashSet<>();
        for (String roleName : request.roles()) {
            RoleName parsed = parseRole(roleName);
            UserRole role = roleRepository
                    .findByName(parsed)
                    .orElseThrow(() -> new ResourceNotFoundException("Role", roleName));
            newRoles.add(role);
        }

        boolean removingOwnAdminRole = user.getUsername().equals(actingUsername)
                && newRoles.stream().noneMatch(role -> role.getName() == RoleName.BANK_ADMIN);
        if (removingOwnAdminRole) {
            throw new BusinessRuleException(
                    "CANNOT_REMOVE_OWN_ADMIN_ROLE",
                    "You cannot remove the administrator role from your own account.");
        }

        String previous = user.getRoles().stream().map(role -> role.getName().name()).sorted().toList().toString();
        user.setRoles(newRoles);
        ApplicationUser saved = userRepository.save(user);

        auditService.success(
                actingUsername,
                currentUserService.currentAuthorities(),
                AuditAction.USER_ROLES_UPDATED,
                "ApplicationUser",
                saved.getUsername(),
                "Roles changed from " + previous + " to " + request.roles());

        return toResponse(saved);
    }

    /** Enables or disables a login (FR-19). */
    @Transactional
    public UserSummaryResponse updateStatus(Long userId, UpdateUserStatusRequest request) {
        String actingUsername = currentUserService.requireUsername();
        ApplicationUser user = requireUser(userId);

        if (user.getUsername().equals(actingUsername) && !request.enabled()) {
            throw new BusinessRuleException(
                    "CANNOT_DISABLE_SELF", "You cannot disable your own login.");
        }

        user.setEnabled(request.enabled());
        ApplicationUser saved = userRepository.save(user);

        auditService.success(
                actingUsername,
                currentUserService.currentAuthorities(),
                AuditAction.USER_STATUS_UPDATED,
                "ApplicationUser",
                saved.getUsername(),
                "Login " + (request.enabled() ? "enabled" : "disabled"));

        return toResponse(saved);
    }

    private ApplicationUser requireUser(Long userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User"));
    }

    private RoleName parseRole(String roleName) {
        try {
            return RoleName.valueOf(roleName.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResourceNotFoundException("Role", roleName);
        }
    }

    private UserSummaryResponse toResponse(ApplicationUser user) {
        String customerNumber = customerRepository
                .findByUserUsername(user.getUsername())
                .map(Customer::getCustomerNumber)
                .orElse(null);

        return new UserSummaryResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getEmail(),
                user.getRoles().stream().map(role -> role.getName().name()).sorted().toList(),
                user.isEnabled(),
                customerNumber,
                user.getCreatedAt());
    }
}
