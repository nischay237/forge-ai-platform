package com.forge.platform.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FindingSuppressionRepository extends JpaRepository<FindingSuppressionEntity, String> {
    List<FindingSuppressionEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<FindingSuppressionEntity> findByTenantIdAndFingerprint(String tenantId, String fingerprint);
    Optional<FindingSuppressionEntity> findByIdAndTenantId(String id, String tenantId);
}
