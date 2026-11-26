package com.forge.platform.prediction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeploymentRecordRepository extends JpaRepository<DeploymentRecordEntity, String> {
    Optional<DeploymentRecordEntity> findByTenantIdAndExternalEventKey(String tenantId, String externalEventKey);
    List<DeploymentRecordEntity> findByTenantIdOrderByDeployedAtDesc(String tenantId);
    List<DeploymentRecordEntity> findByTenantIdAndRepositoryIdOrderByDeployedAtDesc(String tenantId, String repositoryId);
    List<DeploymentRecordEntity> findByTenantIdAndServiceNameIgnoreCaseOrderByDeployedAtDesc(String tenantId, String serviceName);
    long countByTenantId(String tenantId);
}
