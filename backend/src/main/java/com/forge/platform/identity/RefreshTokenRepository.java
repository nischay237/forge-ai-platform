package com.forge.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, String> {
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);
    List<RefreshTokenEntity> findByFamilyIdAndRevokedAtIsNull(String familyId);
    List<RefreshTokenEntity> findByTenantIdAndUserIdAndRevokedAtIsNull(String tenantId, String userId);
}
