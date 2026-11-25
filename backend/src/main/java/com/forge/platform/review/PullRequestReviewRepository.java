package com.forge.platform.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PullRequestReviewRepository extends JpaRepository<PullRequestReviewEntity, String> {
    List<PullRequestReviewEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<PullRequestReviewEntity> findByTenantIdAndRepositoryIdOrderByCreatedAtDesc(String tenantId, String repositoryId);
    Optional<PullRequestReviewEntity> findByIdAndTenantId(String id, String tenantId);
    long countByTenantId(String tenantId);
}
