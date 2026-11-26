package com.forge.platform.prediction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EngineeringEventRepository extends JpaRepository<EngineeringEventEntity, String> {
    Optional<EngineeringEventEntity> findByTenantIdAndExternalEventKey(String tenantId, String externalEventKey);
    List<EngineeringEventEntity> findByTenantIdOrderByOccurredAtDesc(String tenantId);
    List<EngineeringEventEntity> findByTenantIdAndRepositoryIdOrderByOccurredAtDesc(String tenantId, String repositoryId);
    long countByTenantId(String tenantId);
}
