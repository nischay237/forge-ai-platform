package com.forge.platform.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {
    List<AuditLog> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);
    List<AuditLog> findByTenantIdAndActionContainingIgnoreCaseOrderByCreatedAtDesc(String tenantId, String action, Pageable pageable);
    long countByTenantId(String tenantId);
}
