package com.sepro.obfds.repository;

import com.sepro.obfds.entity.Customer;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByUserUsername(String username);

    Optional<Customer> findByCustomerNumber(String customerNumber);

    boolean existsByEmail(String email);
}
