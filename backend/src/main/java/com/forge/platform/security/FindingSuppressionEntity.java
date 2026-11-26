package com.forge.platform.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finding_suppressions")
public class FindingSuppressionEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id")
    private String repositoryId;

    @Column(nullable = false)
    private String fingerprint;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "suppressed_by", nullable = false)
    private String suppressedBy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public FindingSuppressionEntity() {}

    public static FindingSuppressionEntity create(
        String tenantId, String repositoryId, String fingerprint, String ruleId,
        String reason, String suppressedBy, Instant expiresAt
    ) {
        FindingSuppressionEntity s = new FindingSuppressionEntity();
        s.id = UUID.randomUUID().toString();
        s.tenantId = tenantId;
        s.repositoryId = repositoryId;
        s.fingerprint = fingerprint;
        s.ruleId = ruleId;
        s.reason = reason.trim();
        s.suppressedBy = suppressedBy;
        s.expiresAt = expiresAt;
        s.createdAt = Instant.now();
        return s;
    }

    public boolean isActive() {
        return expiresAt == null || expiresAt.isAfter(Instant.now());
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getFingerprint() { return fingerprint; }
    public String getRuleId() { return ruleId; }
    public String getReason() { return reason; }
    public String getSuppressedBy() { return suppressedBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
