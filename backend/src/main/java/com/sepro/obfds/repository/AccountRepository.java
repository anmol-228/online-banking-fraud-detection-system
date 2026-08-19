package com.sepro.obfds.repository;

import com.sepro.obfds.entity.Account;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByCustomerIdOrderByIdAsc(Long customerId);

    Optional<Account> findByIdAndCustomerId(Long id, Long customerId);

    Optional<Account> findByAccountNumber(String accountNumber);

    Optional<Account> findByAccountNumberAndCustomerId(String accountNumber, Long customerId);
}
