package com.forge.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "actor_email", nullable = false)
    private String actorEmail;

    @Column(name = "actor_role", nullable = false)
    private String actorRole;

    @Column(nullable = false)
    private String action;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AuditLog() {}

    public static AuditLog create(String tenantId, String actorEmail, String actorRole,
                                  String action, String resourceType, String resourceId,
                                  String details, String ipAddress) {
        AuditLog log = new AuditLog();
        log.id = UUID.randomUUID().toString();
        log.tenantId = tenantId;
        log.actorEmail = actorEmail;
        log.actorRole = actorRole;
        log.action = action;
        log.resourceType = resourceType;
        log.resourceId = resourceId;
        log.details = details;
        log.ipAddress = ipAddress;
        log.createdAt = Instant.now();
        return log;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getActorEmail() { return actorEmail; }
    public String getActorRole() { return actorRole; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getDetails() { return details; }
    public String getIpAddress() { return ipAddress; }
    public Instant getCreatedAt() { return createdAt; }
}
