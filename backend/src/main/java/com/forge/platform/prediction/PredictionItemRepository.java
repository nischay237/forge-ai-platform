package com.forge.platform.prediction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PredictionItemRepository extends JpaRepository<PredictionItemEntity, String> {
    List<PredictionItemEntity> findByTenantIdAndPredictionRunIdOrderByRiskScoreDesc(String tenantId, String predictionRunId);
    List<PredictionItemEntity> findByTenantIdAndPredictionRunIdIn(String tenantId, List<String> predictionRunIds);
    List<PredictionItemEntity> findByTenantIdAndCategoryOrderByCreatedAtDesc(String tenantId, String category);
}
