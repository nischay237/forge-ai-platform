package com.forge.platform.prediction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DefectRecordRepository extends JpaRepository<DefectRecordEntity, String> {
    Optional<DefectRecordEntity> findByTenantIdAndExternalEventKey(String tenantId, String externalEventKey);
    List<DefectRecordEntity> findByTenantIdOrderByReportedAtDesc(String tenantId);
    List<DefectRecordEntity> findByTenantIdAndRepositoryIdOrderByReportedAtDesc(String tenantId, String repositoryId);
    long countByTenantId(String tenantId);
}
