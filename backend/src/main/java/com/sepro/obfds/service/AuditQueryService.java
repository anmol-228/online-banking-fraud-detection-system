package com.sepro.obfds.service;

import com.sepro.obfds.audit.AuditService;
import com.sepro.obfds.dto.AuditLogResponse;
import com.sepro.obfds.dto.PageResponse;
import com.sepro.obfds.entity.AuditLog;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the audit module, used by the administrator audit screen (FR-20, NFR-10). */
@Service
public class AuditQueryService {

    private final AuditService auditService;

    public AuditQueryService(AuditService auditService) {
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(String username, String action, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200));
        Page<AuditLog> results = auditService.search(username, action, pageRequest);

        List<AuditLogResponse> content = results.getContent().stream()
                .map(entry -> new AuditLogResponse(
                        entry.getId(),
                        entry.getOccurredAt(),
                        entry.getUsername(),
                        entry.getRoles(),
                        entry.getAction(),
                        entry.getEntityType(),
                        entry.getEntityReference(),
                        entry.getDetails(),
                        entry.getOutcome().name()))
                .toList();

        return PageResponse.from(results, content);
    }
}
