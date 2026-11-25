package com.forge.platform.audit;

import com.forge.platform.common.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void record(String tenantId, String actorEmail, String actorRole,
                       String action, String resourceType, String resourceId,
                       String details, String ipAddress) {
        AuditLog entry = AuditLog.create(
            tenantId,
            actorEmail != null ? actorEmail : "system@forge.dev",
            actorRole != null ? actorRole : "SYSTEM",
            action,
            resourceType,
            resourceId,
            details,
            ipAddress != null ? ipAddress : "internal"
        );
        auditLogRepository.save(entry);
    }

    @Transactional
    public void recordCurrent(String action, String resourceType, String resourceId, String details) {
        record(
            TenantContext.requireTenantId(),
            TenantContext.requireEmail(),
            TenantContext.requireRole(),
            action,
            resourceType,
            resourceId,
            details,
            "api"
        );
    }

    @Transactional(readOnly = true)
    public List<AuditLog> listRecent(String tenantId, String actionFilter, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        if (actionFilter != null && !actionFilter.isBlank()) {
            return auditLogRepository.findByTenantIdAndActionContainingIgnoreCaseOrderByCreatedAtDesc(
                tenantId, actionFilter.trim(), PageRequest.of(0, safeLimit));
        }
        return auditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(0, safeLimit));
    }
}
