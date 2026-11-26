package com.forge.platform.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "family_id", nullable = false)
    private String familyId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_hash")
    private String replacedByHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public RefreshTokenEntity() {}

    public static RefreshTokenEntity issue(
        String tenantId, String userId, String familyId, String tokenHash, Instant expiresAt
    ) {
        RefreshTokenEntity rt = new RefreshTokenEntity();
        rt.id = UUID.randomUUID().toString();
        rt.tenantId = tenantId;
        rt.userId = userId;
        rt.familyId = (familyId == null || familyId.isBlank()) ? UUID.randomUUID().toString() : familyId;
        rt.tokenHash = tokenHash;
        rt.expiresAt = expiresAt;
        rt.createdAt = Instant.now();
        return rt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public void revoke(String replacedByHash) {
        this.revokedAt = Instant.now();
        if (replacedByHash != null) {
            this.replacedByHash = replacedByHash;
        }
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public String getFamilyId() { return familyId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getReplacedByHash() { return replacedByHash; }
    public Instant getCreatedAt() { return createdAt; }
}
