package com.sepro.obfds.service;

import com.sepro.obfds.audit.AuditAction;
import com.sepro.obfds.audit.AuditService;
import com.sepro.obfds.dto.AuthResponse;
import com.sepro.obfds.dto.LoginRequest;
import com.sepro.obfds.dto.RegisterRequest;
import com.sepro.obfds.dto.UserProfileResponse;
import com.sepro.obfds.entity.Account;
import com.sepro.obfds.entity.AccountType;
import com.sepro.obfds.entity.ApplicationUser;
import com.sepro.obfds.entity.Customer;
import com.sepro.obfds.entity.RoleName;
import com.sepro.obfds.entity.UserRole;
import com.sepro.obfds.exception.BusinessRuleException;
import com.sepro.obfds.repository.AccountRepository;
import com.sepro.obfds.repository.ApplicationUserRepository;
import com.sepro.obfds.repository.CustomerRepository;
import com.sepro.obfds.repository.UserRoleRepository;
import com.sepro.obfds.security.CurrentUserService;
import com.sepro.obfds.security.JwtService;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user authentication module (FR-01, FR-02, FR-03).
 *
 * <p>Registration creates three linked records at once: the login identity, the banking profile
 * and one opening savings account, so that a newly registered customer can immediately be used
 * for a demonstration.</p>
 */
@Service
public class AuthService {

    /** Opening balance given to a newly registered demo account. No real money is involved. */
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("25000.00");

    private final ApplicationUserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final UserRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final ReferenceGenerator referenceGenerator;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;

    public AuthService(
            ApplicationUserRepository userRepository,
            CustomerRepository customerRepository,
            AccountRepository accountRepository,
            UserRoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            ReferenceGenerator referenceGenerator,
            AuditService auditService,
            CurrentUserService currentUserService) {
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.referenceGenerator = referenceGenerator;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
    }

    /** Registers a new customer and opens their first account (FR-01). */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();

        if (userRepository.existsByUsername(username)) {
            throw new BusinessRuleException(
                    HttpStatus.CONFLICT, "USERNAME_TAKEN", "That username is already registered.");
        }
        if (userRepository.existsByEmail(email) || customerRepository.existsByEmail(email)) {
            throw new BusinessRuleException(
                    HttpStatus.CONFLICT, "EMAIL_TAKEN", "That email address is already registered.");
        }

        UserRole customerRole = roleRepository
                .findByName(RoleName.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role has not been initialised"));

        ApplicationUser user = new ApplicationUser();
        user.setUsername(username);
        // The plain password is hashed here and is never stored or logged anywhere (NFR-01).
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setEnabled(true);
        user.setRoles(new LinkedHashSet<>(Set.of(customerRole)));
        userRepository.save(user);

        Customer customer = new Customer();
        customer.setCustomerNumber(referenceGenerator.customerNumber());
        customer.setFullName(request.fullName().trim());
        customer.setEmail(email);
        customer.setPhone(request.phone());
        customer.setAddress(request.address());
        customer.setUser(user);
        customerRepository.save(customer);

        Account account = new Account();
        account.setAccountNumber(referenceGenerator.accountNumber());
        account.setCustomer(customer);
        account.setAccountType(AccountType.SAVINGS);
        account.setBalance(OPENING_BALANCE);
        accountRepository.save(account);

        auditService.success(
                username,
                List.of("ROLE_CUSTOMER"),
                AuditAction.REGISTER,
                "Customer",
                customer.getCustomerNumber(),
                "New customer registered with opening account " + account.getAccountNumber());

        return buildAuthResponse(user, customer.getCustomerNumber(), List.of(customerRole.authority()));
    }

    /** Authenticates a user and issues a token (FR-02, FR-03). */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String username = request.username().trim();
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.password()));
        } catch (AuthenticationException ex) {
            // A failed attempt is audited before the exception is rethrown, so that repeated
            // failures are visible to an administrator (FR-20).
            auditService.failure(
                    username, List.of(), AuditAction.LOGIN_FAILURE, "ApplicationUser", username,
                    "Login rejected: " + ex.getClass().getSimpleName());
            throw ex;
        }

        ApplicationUser user = userRepository
                .findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Authenticated user disappeared"));

        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        String customerNumber = customerRepository
                .findByUserUsername(username)
                .map(Customer::getCustomerNumber)
                .orElse(null);

        auditService.success(
                username, authorities, AuditAction.LOGIN_SUCCESS, "ApplicationUser", username,
                "Login successful");

        return buildAuthResponse(user, customerNumber, authorities);
    }

    /** Returns the profile of the caller so the front end can restore state after a refresh. */
    @Transactional(readOnly = true)
    public UserProfileResponse currentProfile() {
        ApplicationUser user = currentUserService.requireUser();
        Customer customer = customerRepository.findByUserUsername(user.getUsername()).orElse(null);

        return new UserProfileResponse(
                user.getUsername(),
                user.getFullName(),
                user.getEmail(),
                user.getRoles().stream().map(role -> role.getName().name()).toList(),
                customer == null ? null : customer.getCustomerNumber(),
                customer == null ? null : customer.getPhone(),
                customer == null ? null : customer.getAddress());
    }

    private AuthResponse buildAuthResponse(
            ApplicationUser user, String customerNumber, List<String> authorities) {

        String token = jwtService.issueToken(user.getUsername(), authorities);
        List<String> roleNames = authorities.stream()
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
                .toList();

        return new AuthResponse(
                token,
                jwtService.getExpirySeconds(),
                user.getUsername(),
                user.getFullName(),
                roleNames,
                customerNumber);
    }
}
