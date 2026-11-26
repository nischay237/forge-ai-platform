package com.forge.platform.prediction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prediction_runs")
public class PredictionRunEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id", nullable = false)
    private String repositoryId;

    @Column(name = "review_id", nullable = false)
    private String reviewId;

    @Column(name = "pr_number", nullable = false)
    private int prNumber;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Column(name = "engine_version", nullable = false)
    private String engineVersion;

    @Column(name = "model_type", nullable = false)
    private String modelType;

    @Column(name = "overall_engineering_risk_score", nullable = false)
    private int overallEngineeringRiskScore;

    @Column(name = "overall_risk_level", nullable = false)
    private String overallRiskLevel;

    @Column(name = "signal_breakdown_json", nullable = false, columnDefinition = "TEXT")
    private String signalBreakdownJson;

    @Column(name = "feature_snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String featureSnapshotJson;

    @Column(name = "historical_sample_size", nullable = false)
    private int historicalSampleSize;

    @Column(name = "data_freshness_at")
    private Instant dataFreshnessAt;

    @Column(name = "data_availability_summary", nullable = false, columnDefinition = "TEXT")
    private String dataAvailabilitySummary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public PredictionRunEntity() {}

    public static PredictionRunEntity create(
        String tenantId, String repositoryId, String reviewId, int prNumber, String commitSha,
        String engineVersion, String modelType, int overallEngineeringRiskScore, String overallRiskLevel,
        String signalBreakdownJson, String featureSnapshotJson, int historicalSampleSize,
        Instant dataFreshnessAt, String dataAvailabilitySummary
    ) {
        PredictionRunEntity r = new PredictionRunEntity();
        r.id = UUID.randomUUID().toString();
        r.tenantId = tenantId;
        r.repositoryId = repositoryId;
        r.reviewId = reviewId;
        r.prNumber = prNumber;
        r.commitSha = commitSha;
        r.engineVersion = engineVersion;
        r.modelType = modelType;
        r.overallEngineeringRiskScore = Math.max(0, Math.min(100, overallEngineeringRiskScore));
        r.overallRiskLevel = overallRiskLevel;
        r.signalBreakdownJson = signalBreakdownJson;
        r.featureSnapshotJson = featureSnapshotJson;
        r.historicalSampleSize = Math.max(0, historicalSampleSize);
        r.dataFreshnessAt = dataFreshnessAt;
        r.dataAvailabilitySummary = dataAvailabilitySummary;
        r.createdAt = Instant.now();
        return r;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getReviewId() { return reviewId; }
    public int getPrNumber() { return prNumber; }
    public String getCommitSha() { return commitSha; }
    public String getEngineVersion() { return engineVersion; }
    public String getModelType() { return modelType; }
    public int getOverallEngineeringRiskScore() { return overallEngineeringRiskScore; }
    public String getOverallRiskLevel() { return overallRiskLevel; }
    public String getSignalBreakdownJson() { return signalBreakdownJson; }
    public String getFeatureSnapshotJson() { return featureSnapshotJson; }
    public int getHistoricalSampleSize() { return historicalSampleSize; }
    public Instant getDataFreshnessAt() { return dataFreshnessAt; }
    public String getDataAvailabilitySummary() { return dataAvailabilitySummary; }
    public Instant getCreatedAt() { return createdAt; }
}
