package com.forge.platform.ci;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ci_builds")
public class CiBuildEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id", nullable = false)
    private String repositoryId;

    @Column(name = "pipeline_name", nullable = false)
    private String pipelineName;

    @Column(nullable = false)
    private String branch;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(nullable = false)
    private String status;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "failure_category")
    private String failureCategory;

    @Column(name = "root_cause_summary", columnDefinition = "TEXT")
    private String rootCauseSummary;

    @Column(name = "remediation_steps", columnDefinition = "TEXT")
    private String remediationSteps;

    @Column(name = "raw_log", nullable = false, columnDefinition = "TEXT")
    private String rawLog;

    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    public CiBuildEntity() {}

    public static CiBuildEntity create(
        String tenantId, String repositoryId, String pipelineName, String branch,
        String commitSha, Integer prNumber, String status, int durationSeconds,
        String failureCategory, String rootCauseSummary, String remediationSteps, String rawLog
    ) {
        CiBuildEntity b = new CiBuildEntity();
        b.id = UUID.randomUUID().toString();
        b.tenantId = tenantId;
        b.repositoryId = repositoryId;
        b.pipelineName = pipelineName;
        b.branch = branch;
        b.commitSha = commitSha;
        b.prNumber = prNumber;
        b.status = status;
        b.durationSeconds = Math.max(1, durationSeconds);
        b.failureCategory = failureCategory;
        b.rootCauseSummary = rootCauseSummary;
        b.remediationSteps = remediationSteps;
        b.rawLog = rawLog;
        b.triggeredAt = Instant.now();
        return b;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getPipelineName() { return pipelineName; }
    public String getBranch() { return branch; }
    public String getCommitSha() { return commitSha; }
    public Integer getPrNumber() { return prNumber; }
    public String getStatus() { return status; }
    public int getDurationSeconds() { return durationSeconds; }
    public String getFailureCategory() { return failureCategory; }
    public String getRootCauseSummary() { return rootCauseSummary; }
    public String getRemediationSteps() { return remediationSteps; }
    public String getRawLog() { return rawLog; }
    public Instant getTriggeredAt() { return triggeredAt; }
}
