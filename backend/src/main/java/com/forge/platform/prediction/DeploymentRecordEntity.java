package com.forge.platform.prediction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deployment_records")
public class DeploymentRecordEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id", nullable = false)
    private String repositoryId;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(nullable = false)
    private String environment;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "commit_sha")
    private String commitSha;

    @Column(nullable = false)
    private String outcome;

    @Column(name = "rollback_of_deployment_id")
    private String rollbackOfDeploymentId;

    @Column(name = "changed_config", nullable = false)
    private boolean changedConfig;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "external_event_key")
    private String externalEventKey;

    @Column(name = "deployed_at", nullable = false)
    private Instant deployedAt;

    public DeploymentRecordEntity() {}

    public static DeploymentRecordEntity create(
        String tenantId, String repositoryId, String serviceName, String environment,
        Integer prNumber, String commitSha, String outcome, String rollbackOfDeploymentId,
        boolean changedConfig, String summary, String externalEventKey, Instant deployedAt
    ) {
        DeploymentRecordEntity d = new DeploymentRecordEntity();
        d.id = UUID.randomUUID().toString();
        d.tenantId = tenantId;
        d.repositoryId = repositoryId;
        d.serviceName = serviceName;
        d.environment = (environment == null || environment.isBlank()) ? "production" : environment.trim();
        d.prNumber = prNumber;
        d.commitSha = commitSha;
        d.outcome = outcome;
        d.rollbackOfDeploymentId = rollbackOfDeploymentId;
        d.changedConfig = changedConfig;
        d.summary = summary;
        d.externalEventKey = externalEventKey;
        d.deployedAt = deployedAt != null ? deployedAt : Instant.now();
        return d;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getServiceName() { return serviceName; }
    public String getEnvironment() { return environment; }
    public Integer getPrNumber() { return prNumber; }
    public String getCommitSha() { return commitSha; }
    public String getOutcome() { return outcome; }
    public String getRollbackOfDeploymentId() { return rollbackOfDeploymentId; }
    public boolean isChangedConfig() { return changedConfig; }
    public String getSummary() { return summary; }
    public String getExternalEventKey() { return externalEventKey; }
    public Instant getDeployedAt() { return deployedAt; }
}
