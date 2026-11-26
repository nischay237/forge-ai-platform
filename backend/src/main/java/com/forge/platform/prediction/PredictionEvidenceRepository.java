package com.forge.platform.prediction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PredictionEvidenceRepository extends JpaRepository<PredictionEvidenceEntity, String> {
    List<PredictionEvidenceEntity> findByTenantIdAndPredictionItemIdOrderByWeightDesc(String tenantId, String predictionItemId);
    List<PredictionEvidenceEntity> findByTenantIdAndPredictionItemIdIn(String tenantId, List<String> predictionItemIds);
}
