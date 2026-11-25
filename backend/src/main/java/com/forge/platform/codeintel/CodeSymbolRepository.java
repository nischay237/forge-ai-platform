package com.forge.platform.codeintel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CodeSymbolRepository extends JpaRepository<CodeSymbolEntity, String> {
    List<CodeSymbolEntity> findByRepositoryIdAndTenantIdOrderByFilePathAscStartLineAsc(String repositoryId, String tenantId);
    void deleteByRepositoryIdAndTenantId(String repositoryId, String tenantId);
}
