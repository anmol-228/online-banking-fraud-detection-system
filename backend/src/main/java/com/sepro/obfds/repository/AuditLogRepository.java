package com.sepro.obfds.repository;

import com.sepro.obfds.entity.AuditLog;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("select a from AuditLog a "
            + "where (:username is null or a.username = :username) "
            + "and (:action is null or a.action = :action) "
            + "order by a.occurredAt desc")
    Page<AuditLog> search(
            @Param("username") String username, @Param("action") String action, Pageable pageable);

    long countByAction(String action);

    /** Rows about any of the given entities (a case, its alert and its transfer), oldest first. */
    List<AuditLog> findByEntityReferenceInOrderByOccurredAtAscIdAsc(java.util.Collection<String> references);

    /** How many times a user performed an action since a moment; used for per-user daily limits. */
    long countByUsernameAndActionAndOccurredAtAfter(String username, String action, java.time.Instant since);

    boolean existsByActionAndEntityReference(String action, String entityReference);
}
