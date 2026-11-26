package com.forge.platform.prediction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IncidentRecordRepository extends JpaRepository<IncidentRecordEntity, String> {
    Optional<IncidentRecordEntity> findByTenantIdAndExternalEventKey(String tenantId, String externalEventKey);
    List<IncidentRecordEntity> findByTenantIdOrderByOccurredAtDesc(String tenantId);
    List<IncidentRecordEntity> findByTenantIdAndRepositoryIdOrderByOccurredAtDesc(String tenantId, String repositoryId);
    List<IncidentRecordEntity> findByTenantIdAndServiceNameIgnoreCaseOrderByOccurredAtDesc(String tenantId, String serviceName);
    long countByTenantId(String tenantId);
}
