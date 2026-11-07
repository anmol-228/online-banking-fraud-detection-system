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
    /**
     * Money currently promised to held transfers. A transfer waiting for a verification code whose
     * code has already expired no longer counts: it can never be released, so reserving funds for
     * it would only block the customer. {@code now} comes from the application clock, never from
     * the database server (whose session time zone differs between deployments).
     */
    @Query("select coalesce(sum(t.amount), 0) from Transaction t "
            + "where t.sourceAccount.id = :accountId "
            + "and (t.status = com.sepro.obfds.entity.TransactionStatus.PENDING "
            + "  or (t.status = com.sepro.obfds.entity.TransactionStatus.PENDING_VERIFICATION "
            + "      and not exists (select v.id from VerificationRequest v where v.transaction = t "
            + "          and v.status = com.sepro.obfds.entity.VerificationStatus.PENDING and v.expiresAt < :now)))")
    BigDecimal sumReservedAmount(@Param("accountId") Long accountId, @Param("now") Instant now);

    /** Holds on this account whose verification code expired without being used. */
    @Query("select t from Transaction t "
            + "where t.sourceAccount.id = :accountId "
            + "and t.status = com.sepro.obfds.entity.TransactionStatus.PENDING_VERIFICATION "
            + "and exists (select v.id from VerificationRequest v where v.transaction = t "
            + "    and v.status = com.sepro.obfds.entity.VerificationStatus.PENDING and v.expiresAt < :now)")
    List<Transaction> findExpiredVerificationHolds(@Param("accountId") Long accountId, @Param("now") Instant now);

    /** Same predicate across all accounts, so reports agree with balances. */
    @Query("select count(t) from Transaction t "
            + "where t.status = com.sepro.obfds.entity.TransactionStatus.PENDING_VERIFICATION "
            + "and exists (select v.id from VerificationRequest v where v.transaction = t "
            + "    and v.status = com.sepro.obfds.entity.VerificationStatus.PENDING and v.expiresAt < :now)")
    long countExpiredVerificationHolds(@Param("now") Instant now);

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
