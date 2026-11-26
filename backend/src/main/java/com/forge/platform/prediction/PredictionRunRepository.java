package com.forge.platform.prediction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PredictionRunRepository extends JpaRepository<PredictionRunEntity, String> {
    Optional<PredictionRunEntity> findFirstByTenantIdAndReviewIdOrderByCreatedAtDesc(String tenantId, String reviewId);
    List<PredictionRunEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<PredictionRunEntity> findByTenantIdAndRepositoryIdOrderByCreatedAtDesc(String tenantId, String repositoryId);
    List<PredictionRunEntity> findByTenantIdAndReviewIdOrderByCreatedAtDesc(String tenantId, String reviewId);
    long countByTenantId(String tenantId);
}
