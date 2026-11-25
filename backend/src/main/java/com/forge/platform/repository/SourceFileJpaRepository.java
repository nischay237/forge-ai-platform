package com.forge.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourceFileJpaRepository extends JpaRepository<SourceFileEntity, String> {
    List<SourceFileEntity> findByRepositoryIdAndTenantIdOrderByFilePathAsc(String repositoryId, String tenantId);
    Optional<SourceFileEntity> findByRepositoryIdAndTenantIdAndFilePath(String repositoryId, String tenantId, String filePath);
    void deleteByRepositoryIdAndTenantId(String repositoryId, String tenantId);
    long countByRepositoryIdAndTenantId(String repositoryId, String tenantId);
}
