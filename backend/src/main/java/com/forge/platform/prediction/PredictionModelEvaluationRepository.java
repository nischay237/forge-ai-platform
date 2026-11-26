package com.forge.platform.prediction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PredictionModelEvaluationRepository extends JpaRepository<PredictionModelEvaluationEntity, String> {
    List<PredictionModelEvaluationEntity> findByTenantIdOrderByEvaluatedAtDesc(String tenantId);
    List<PredictionModelEvaluationEntity> findByTenantIdAndCategoryOrderByEvaluatedAtDesc(String tenantId, String category);
}
