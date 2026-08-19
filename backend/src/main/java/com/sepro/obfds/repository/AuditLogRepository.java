package com.sepro.obfds.repository;

import com.sepro.obfds.entity.AuditLog;
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
}
