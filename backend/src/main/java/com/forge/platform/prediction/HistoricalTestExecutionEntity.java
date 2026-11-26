package com.forge.platform.prediction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "historical_test_executions")
public class HistoricalTestExecutionEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id", nullable = false)
    private String repositoryId;

    @Column(name = "ci_build_id")
    private String ciBuildId;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "commit_sha")
    private String commitSha;

    @Column(name = "test_name", nullable = false)
    private String testName;

    @Column(name = "test_file_path")
    private String testFilePath;

    @Column(name = "module_name")
    private String moduleName;

    @Column(nullable = false)
    private String outcome;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;

    @Column(name = "external_event_key")
    private String externalEventKey;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    public HistoricalTestExecutionEntity() {}

    public static HistoricalTestExecutionEntity create(
        String tenantId, String repositoryId, String ciBuildId, Integer prNumber, String commitSha,
        String testName, String testFilePath, String moduleName, String outcome,
        long durationMs, String errorSummary, String externalEventKey, Instant executedAt
    ) {
        HistoricalTestExecutionEntity t = new HistoricalTestExecutionEntity();
        t.id = UUID.randomUUID().toString();
        t.tenantId = tenantId;
        t.repositoryId = repositoryId;
        t.ciBuildId = ciBuildId;
        t.prNumber = prNumber;
        t.commitSha = commitSha;
        t.testName = testName;
        t.testFilePath = testFilePath;
        t.moduleName = moduleName;
        t.outcome = outcome;
        t.durationMs = Math.max(0L, durationMs);
        t.errorSummary = errorSummary;
        t.externalEventKey = externalEventKey;
        t.executedAt = executedAt != null ? executedAt : Instant.now();
        return t;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getCiBuildId() { return ciBuildId; }
    public Integer getPrNumber() { return prNumber; }
    public String getCommitSha() { return commitSha; }
    public String getTestName() { return testName; }
    public String getTestFilePath() { return testFilePath; }
    public String getModuleName() { return moduleName; }
    public String getOutcome() { return outcome; }
    public long getDurationMs() { return durationMs; }
    public String getErrorSummary() { return errorSummary; }
    public String getExternalEventKey() { return externalEventKey; }
    public Instant getExecutedAt() { return executedAt; }
}
