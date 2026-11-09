package com.sepro.obfds.service;

import com.sepro.obfds.entity.Account;
import com.sepro.obfds.entity.FraudCase;
import com.sepro.obfds.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Repository;

/**
 * The one place that takes database row locks, so the lock <em>order</em> is a property of the
 * code and not of whoever wrote the last caller.
 *
 * <h2>Why locking first is the fix</h2>
 * <p>A fund check reads the balance and the amount already promised to held transfers, then a
 * hold is written. Without a lock, two requests can both read "100,000 available" before either
 * has written its hold, and both succeed - 120,000 promised from 100,000. Taking a write lock on
 * the source account <em>before</em> that read turns the read-then-write into one critical
 * section per account: the second request waits, and when it gets the lock it sees the first
 * one's hold. Optimistic version checks alone cannot do this, because the second request commits
 * a <em>different row</em> (its own new transfer) and so never conflicts.</p>
 *
 * <h2>Lock order (deadlock avoidance)</h2>
 * <ol>
 *   <li>a fraud case, if the operation has one;</li>
 *   <li>then accounts, lowest id first.</li>
 * </ol>
 * <p>Every code path that locks more than one row follows this order, so two requests can never
 * each hold what the other is waiting for.</p>
 *
 * <h2>Why refresh</h2>
 * <p>A row may already be in the persistence context (loaded earlier in the same transaction to
 * check ownership). A plain locking query would return that stale instance. {@code refresh} with a
 * lock mode issues {@code SELECT ... FOR UPDATE} and overwrites the instance with the locked row, so
 * what the caller reads next is the committed truth. This relies on READ_COMMITTED (set on the
 * outermost transactional methods): under REPEATABLE READ an earlier consistent read could pin an
 * older snapshot for plain reads that follow.</p>
 *
 * <p>Annotated {@code @Repository} so a lock timeout or deadlock surfaces as Spring's
 * {@code PessimisticLockingFailureException}, which the API answers with a retryable 409.</p>
 */
@Repository
public class RowLocker {

    private final EntityManager entityManager;

    public RowLocker(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Locks the given accounts in ascending id order and returns them keyed by id. Null and
     * duplicate ids are ignored. Must be called inside a transaction.
     */
    public Map<Long, Account> lockAccounts(Long... accountIds) {
        TreeSet<Long> ordered = new TreeSet<>();
        for (Long id : accountIds) {
            if (id != null) {
                ordered.add(id);
            }
        }
        Map<Long, Account> locked = new LinkedHashMap<>();
        for (Long id : ordered) {
            Account account = entityManager.find(Account.class, id);
            if (account == null) {
                throw new ResourceNotFoundException("Account");
            }
            entityManager.refresh(account, LockModeType.PESSIMISTIC_WRITE);
            locked.put(id, account);
        }
        return locked;
    }

    /** Locks one fraud case by its public reference. Take this before any account lock. */
    public FraudCase lockCase(String reference) {
        List<FraudCase> found = entityManager
                .createQuery("select c from FraudCase c where c.reference = :reference", FraudCase.class)
                .setParameter("reference", reference)
                .getResultList();
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Fraud case", reference);
        }
        FraudCase fraudCase = found.get(0);
        entityManager.refresh(fraudCase, LockModeType.PESSIMISTIC_WRITE);
        return fraudCase;
    }

    /** Re-reads an entity from the database, discarding any stale copy held in this transaction. */
    public <T> T reload(T entity) {
        entityManager.refresh(entity);
        return entity;
    }
}
