package com.forge.platform.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewFindingRepository extends JpaRepository<ReviewFindingEntity, String> {
    List<ReviewFindingEntity> findByReviewIdAndTenantIdOrderBySeverityAscLineNumberAsc(String reviewId, String tenantId);
    List<ReviewFindingEntity> findByTenantId(String tenantId);
    Optional<ReviewFindingEntity> findByIdAndReviewIdAndTenantId(String id, String reviewId, String tenantId);
    long countByReviewIdAndTenantId(String reviewId, String tenantId);
    long countByTenantId(String tenantId);
}
