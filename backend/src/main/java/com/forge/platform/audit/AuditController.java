package com.forge.platform.audit;

import com.forge.platform.common.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public List<AuditLog> listAuditLogs(
        @RequestParam(required = false) String action,
        @RequestParam(defaultValue = "80") int limit
    ) {
        return auditService.listRecent(TenantContext.requireTenantId(), action, limit);
    }
}
