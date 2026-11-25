package com.forge.platform.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvaluationRunRepository extends JpaRepository<EvaluationRunEntity, String> {
    List<EvaluationRunEntity> findByTenantIdOrderByExecutedAtDesc(String tenantId);
}
