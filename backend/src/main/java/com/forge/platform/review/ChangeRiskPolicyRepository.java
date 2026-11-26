package com.forge.platform.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChangeRiskPolicyRepository extends JpaRepository<ChangeRiskPolicyEntity, String> {
    Optional<ChangeRiskPolicyEntity> findFirstByTenantIdAndRepositoryIdOrderByVersionDesc(String tenantId, String repositoryId);
    Optional<ChangeRiskPolicyEntity> findFirstByTenantIdAndRepositoryIdIsNullOrderByVersionDesc(String tenantId);
}
