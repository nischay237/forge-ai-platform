package com.forge.platform.codeintel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CodeDependencyRepository extends JpaRepository<CodeDependencyEntity, String> {
    List<CodeDependencyEntity> findByRepositoryIdAndTenantId(String repositoryId, String tenantId);
    void deleteByRepositoryIdAndTenantId(String repositoryId, String tenantId);
}
