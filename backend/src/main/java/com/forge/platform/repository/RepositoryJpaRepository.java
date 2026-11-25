package com.forge.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepositoryJpaRepository extends JpaRepository<RepositoryEntity, String> {
    List<RepositoryEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<RepositoryEntity> findByIdAndTenantId(String id, String tenantId);
    Optional<RepositoryEntity> findByTenantIdAndFullNameIgnoreCase(String tenantId, String fullName);
    long countByTenantId(String tenantId);
}
