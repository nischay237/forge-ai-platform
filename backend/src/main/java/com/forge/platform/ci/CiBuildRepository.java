package com.forge.platform.ci;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CiBuildRepository extends JpaRepository<CiBuildEntity, String> {
    List<CiBuildEntity> findByTenantIdOrderByTriggeredAtDesc(String tenantId);
    List<CiBuildEntity> findByTenantIdAndRepositoryIdOrderByTriggeredAtDesc(String tenantId, String repositoryId);
    Optional<CiBuildEntity> findByIdAndTenantId(String id, String tenantId);
}
