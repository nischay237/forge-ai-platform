package com.forge.platform.identity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, String> {
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshTokenEntity r WHERE r.tokenHash = :tokenHash")
    Optional<RefreshTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE RefreshTokenEntity r
        SET r.revokedAt = :revokedAt, r.replacedByHash = :replacedByHash
        WHERE r.id = :id AND r.revokedAt IS NULL
        """)
    int revokeIfActive(
        @Param("id") String id,
        @Param("revokedAt") Instant revokedAt,
        @Param("replacedByHash") String replacedByHash
    );

    List<RefreshTokenEntity> findByFamilyIdAndRevokedAtIsNull(String familyId);
    List<RefreshTokenEntity> findByTenantIdAndUserIdAndRevokedAtIsNull(String tenantId, String userId);
}
