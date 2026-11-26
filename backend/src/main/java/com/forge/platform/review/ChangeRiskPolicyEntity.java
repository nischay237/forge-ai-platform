package com.forge.platform.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "change_risk_policies")
public class ChangeRiskPolicyEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id")
    private String repositoryId;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "block_on_critical_security", nullable = false)
    private boolean blockOnCriticalSecurity = true;

    @Column(name = "block_on_breaking_api", nullable = false)
    private boolean blockOnBreakingApi = true;

    @Column(name = "block_on_destructive_migration", nullable = false)
    private boolean blockOnDestructiveMigration = true;

    @Column(name = "block_on_prompt_injection", nullable = false)
    private boolean blockOnPromptInjection = true;

    @Column(name = "max_risk_score_for_pass", nullable = false)
    private int maxRiskScoreForPass = 25;

    @Column(name = "max_risk_score_for_warn", nullable = false)
    private int maxRiskScoreForWarn = 65;

    @Column(name = "min_tests_for_high_impact", nullable = false)
    private boolean minTestsForHighImpact = true;

    @Column(name = "sensitive_paths_json", columnDefinition = "TEXT")
    private String sensitivePathsJson;

    @Column(name = "architecture_rules_json", columnDefinition = "TEXT")
    private String architectureRulesJson;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ChangeRiskPolicyEntity() {}

    public static ChangeRiskPolicyEntity defaultPolicy(String tenantId, String repositoryId, String updatedBy) {
        ChangeRiskPolicyEntity p = new ChangeRiskPolicyEntity();
        p.id = UUID.randomUUID().toString();
        p.tenantId = tenantId;
        p.repositoryId = repositoryId;
        p.version = 1;
        p.blockOnCriticalSecurity = true;
        p.blockOnBreakingApi = true;
        p.blockOnDestructiveMigration = true;
        p.blockOnPromptInjection = true;
        p.maxRiskScoreForPass = 25;
        p.maxRiskScoreForWarn = 65;
        p.minTestsForHighImpact = true;
        p.sensitivePathsJson = "[\"auth\",\"security\",\"payment\",\"ledger\",\"settlement\",\"crypto\",\"migration\"]";
        p.architectureRulesJson = "[{\"id\":\"ARCH-001\",\"sourceLayer\":\"API\",\"forbiddenTargetLayer\":\"DATA\",\"severity\":\"HIGH\",\"description\":\"API controllers should not bypass Service layer to depend directly on DATA repositories\"},{\"id\":\"ARCH-002\",\"sourceLayer\":\"DATA\",\"forbiddenTargetLayer\":\"API\",\"severity\":\"CRITICAL\",\"description\":\"DATA persistence layer must never depend on API/Controller layer\"},{\"id\":\"ARCH-003\",\"sourceLayer\":\"DOMAIN\",\"forbiddenTargetLayer\":\"API\",\"severity\":\"HIGH\",\"description\":\"DOMAIN layer must remain independent of API transport layer\"}]";
        p.updatedBy = updatedBy;
        p.updatedAt = Instant.now();
        return p;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public boolean isBlockOnCriticalSecurity() { return blockOnCriticalSecurity; }
    public void setBlockOnCriticalSecurity(boolean blockOnCriticalSecurity) { this.blockOnCriticalSecurity = blockOnCriticalSecurity; }
    public boolean isBlockOnBreakingApi() { return blockOnBreakingApi; }
    public void setBlockOnBreakingApi(boolean blockOnBreakingApi) { this.blockOnBreakingApi = blockOnBreakingApi; }
    public boolean isBlockOnDestructiveMigration() { return blockOnDestructiveMigration; }
    public void setBlockOnDestructiveMigration(boolean blockOnDestructiveMigration) { this.blockOnDestructiveMigration = blockOnDestructiveMigration; }
    public boolean isBlockOnPromptInjection() { return blockOnPromptInjection; }
    public void setBlockOnPromptInjection(boolean blockOnPromptInjection) { this.blockOnPromptInjection = blockOnPromptInjection; }
    public int getMaxRiskScoreForPass() { return maxRiskScoreForPass; }
    public void setMaxRiskScoreForPass(int maxRiskScoreForPass) { this.maxRiskScoreForPass = maxRiskScoreForPass; }
    public int getMaxRiskScoreForWarn() { return maxRiskScoreForWarn; }
    public void setMaxRiskScoreForWarn(int maxRiskScoreForWarn) { this.maxRiskScoreForWarn = maxRiskScoreForWarn; }
    public boolean isMinTestsForHighImpact() { return minTestsForHighImpact; }
    public void setMinTestsForHighImpact(boolean minTestsForHighImpact) { this.minTestsForHighImpact = minTestsForHighImpact; }
    public String getSensitivePathsJson() { return sensitivePathsJson; }
    public void setSensitivePathsJson(String sensitivePathsJson) { this.sensitivePathsJson = sensitivePathsJson; }
    public String getArchitectureRulesJson() { return architectureRulesJson; }
    public void setArchitectureRulesJson(String architectureRulesJson) { this.architectureRulesJson = architectureRulesJson; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
