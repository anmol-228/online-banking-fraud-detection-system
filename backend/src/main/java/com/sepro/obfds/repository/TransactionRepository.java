package com.sepro.obfds.repository;

import com.sepro.obfds.entity.RiskLevel;
import com.sepro.obfds.entity.Transaction;
import com.sepro.obfds.entity.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByReference(String reference);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /** Transaction history for one customer, newest first (FR-10). */
    @Query("select t from Transaction t where t.sourceAccount.customer.id = :customerId")
    Page<Transaction> findByCustomer(@Param("customerId") Long customerId, Pageable pageable);

    @Query("select t from Transaction t where t.sourceAccount.customer.id = :customerId")
    List<Transaction> findAllByCustomer(@Param("customerId") Long customerId);

    /**
     * Total amount still reserved by transfers that have been accepted but not yet completed.
     * The available balance of an account is its balance minus this figure (NFR-09).
     */
    @Query("select coalesce(sum(t.amount), 0) from Transaction t "
            + "where t.sourceAccount.id = :accountId "
            + "and t.status in (com.sepro.obfds.entity.TransactionStatus.PENDING, "
            + "com.sepro.obfds.entity.TransactionStatus.PENDING_VERIFICATION)")
    BigDecimal sumReservedAmount(@Param("accountId") Long accountId);

    /** Used by the transaction-velocity fraud rule (FR-11). */
    long countBySourceAccountCustomerIdAndCreatedAtAfter(Long customerId, Instant after);

    /** Used to detect a repeated submission of the same transfer (TC-14). */
    Optional<Transaction> findFirstBySourceAccountIdAndDestinationAccountNumberAndAmountAndCreatedAtAfterOrderByIdDesc(
            Long sourceAccountId, String destinationAccountNumber, BigDecimal amount, Instant after);

    List<Transaction> findByStatusOrderByCreatedAtDesc(TransactionStatus status);

    long countByStatus(TransactionStatus status);

    long countByRiskLevel(RiskLevel riskLevel);

    @Query("select coalesce(sum(t.amount), 0) from Transaction t where t.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") TransactionStatus status);
}
