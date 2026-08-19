package com.sepro.obfds.audit;

import com.sepro.obfds.entity.AuditLog;
import com.sepro.obfds.entity.AuditOutcome;
import com.sepro.obfds.repository.AuditLogRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit trail (FR-20, NFR-10).
 *
 * <p>Audit writing runs in its own transaction. A failed login attempt must still be recorded
 * even though the surrounding operation is being rejected, and a rolled back transfer must still
 * leave evidence that it was attempted.</p>
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String username,
            List<String> roles,
            String action,
            String entityType,
            String entityReference,
            String details,
            AuditOutcome outcome) {

        AuditLog entry = new AuditLog();
        entry.setOccurredAt(Instant.now());
        entry.setUsername(username == null ? "anonymous" : username);
        entry.setRoles(roles == null || roles.isEmpty() ? null : String.join(",", roles));
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityReference(entityReference);
        entry.setDetails(truncate(details));
        entry.setOutcome(outcome);
        auditLogRepository.save(entry);
    }

    /**
     * Records a successful action.
     *
     * <p>The propagation setting is repeated here rather than relied upon from {@link #record}.
     * A call from this class to {@code record} does not pass through the Spring proxy, so without
     * the annotation on the entry point the write would silently join the transaction of the
     * caller and be rolled back with it.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(
            String username, List<String> roles, String action, String entityType,
            String entityReference, String details) {
        record(username, roles, action, entityType, entityReference, details, AuditOutcome.SUCCESS);
    }

    /**
     * Records a rejected or failed action.
     *
     * <p>This is the case that matters most: a failed login or a failed verification is audited
     * on a path that then throws, so the entry has to be committed independently of the operation
     * that is being rejected (FR-20).</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(
            String username, List<String> roles, String action, String entityType,
            String entityReference, String details) {
        record(username, roles, action, entityType, entityReference, details, AuditOutcome.FAILURE);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> search(String username, String action, Pageable pageable) {
        String normalisedUsername = (username == null || username.isBlank()) ? null : username.trim();
        String normalisedAction = (action == null || action.isBlank()) ? null : action.trim();
        return auditLogRepository.search(normalisedUsername, normalisedAction, pageable);
    }

    private String truncate(String details) {
        if (details == null) {
            return null;
        }
        return details.length() <= 500 ? details : details.substring(0, 497) + "...";
    }
}
