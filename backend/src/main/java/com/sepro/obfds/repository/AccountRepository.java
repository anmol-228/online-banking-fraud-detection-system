package com.sepro.obfds.repository;

import com.sepro.obfds.entity.Account;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByCustomerIdOrderByIdAsc(Long customerId);

    Optional<Account> findByIdAndCustomerId(Long id, Long customerId);

    Optional<Account> findByAccountNumber(String accountNumber);

    Optional<Account> findByAccountNumberAndCustomerId(String accountNumber, Long customerId);

    /**
     * Id-only lookups. They deliberately do not load the {@link Account}: an account read before
     * its row is locked would sit in the persistence context with a stale balance. The lock is
     * taken afterwards through {@code RowLocker}, which re-reads the row.
     */
    @Query("select a.id from Account a where a.accountNumber = :accountNumber and a.customer.id = :customerId")
    Optional<Long> findIdByAccountNumberAndCustomerId(
            @Param("accountNumber") String accountNumber, @Param("customerId") Long customerId);

    @Query("select a.id from Account a where a.accountNumber = :accountNumber")
    Optional<Long> findIdByAccountNumber(@Param("accountNumber") String accountNumber);
}
