package com.sepro.obfds.security;

import com.sepro.obfds.entity.ApplicationUser;
import com.sepro.obfds.entity.Customer;
import com.sepro.obfds.exception.ApiException;
import com.sepro.obfds.repository.ApplicationUserRepository;
import com.sepro.obfds.repository.CustomerRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the caller of the current request.
 *
 * <p>Services use this instead of trusting any identifier sent by the client, which is what stops
 * one customer from reading the data of another customer (NFR-08).</p>
 */
@Service
public class CurrentUserService {

    private final ApplicationUserRepository userRepository;
    private final CustomerRepository customerRepository;

    public CurrentUserService(
            ApplicationUserRepository userRepository, CustomerRepository customerRepository) {
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
    }

    public String requireUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "No authenticated user.");
        }
        return authentication.getName();
    }

    public List<String> currentAuthorities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return List.of();
        }
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Transactional(readOnly = true)
    public ApplicationUser requireUser() {
        String username = requireUsername();
        return userRepository
                .findByUsername(username)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authenticated user no longer exists."));
    }

    /** The banking profile of the caller. Only customers have one. */
    @Transactional(readOnly = true)
    public Customer requireCustomer() {
        String username = requireUsername();
        return customerRepository
                .findByUserUsername(username)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.FORBIDDEN,
                        "NOT_A_CUSTOMER",
                        "This operation is only available to bank customers."));
    }
}
