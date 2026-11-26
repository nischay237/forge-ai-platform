package com.forge.platform.prediction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HistoricalTestExecutionRepository extends JpaRepository<HistoricalTestExecutionEntity, String> {
    Optional<HistoricalTestExecutionEntity> findByTenantIdAndExternalEventKey(String tenantId, String externalEventKey);
    List<HistoricalTestExecutionEntity> findByTenantIdOrderByExecutedAtDesc(String tenantId);
    List<HistoricalTestExecutionEntity> findByTenantIdAndRepositoryIdOrderByExecutedAtDesc(String tenantId, String repositoryId);
    List<HistoricalTestExecutionEntity> findByTenantIdAndRepositoryIdAndPrNumberOrderByExecutedAtDesc(
        String tenantId, String repositoryId, Integer prNumber
    );
    long countByTenantId(String tenantId);
}
