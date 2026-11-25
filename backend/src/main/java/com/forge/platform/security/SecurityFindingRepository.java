package com.forge.platform.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SecurityFindingRepository extends JpaRepository<SecurityFindingEntity, String> {
    List<SecurityFindingEntity> findByTenantIdOrderByDetectedAtDesc(String tenantId);
    List<SecurityFindingEntity> findByTenantIdAndRepositoryIdOrderByDetectedAtDesc(String tenantId, String repositoryId);
    Optional<SecurityFindingEntity> findByIdAndTenantId(String id, String tenantId);
    long countByTenantIdAndStatus(String tenantId, String status);
    long countByTenantIdAndSeverity(String tenantId, String severity);
}
