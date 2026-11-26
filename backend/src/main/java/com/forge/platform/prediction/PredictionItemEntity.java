package com.forge.platform.prediction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prediction_items")
public class PredictionItemEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "prediction_run_id", nullable = false)
    private String predictionRunId;

    @Column(nullable = false)
    private String category;

    @Column(name = "risk_level", nullable = false)
    private String riskLevel;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(precision = 5, scale = 4)
    private BigDecimal probability;

    @Column(name = "confidence_level", nullable = false)
    private String confidenceLevel;

    @Column(name = "insufficient_data", nullable = false)
    private boolean insufficientData;

    @Column(name = "historical_sample_size", nullable = false)
    private int historicalSampleSize;

    @Column(name = "data_freshness_at")
    private Instant dataFreshnessAt;

    @Column(name = "rule_or_model_version", nullable = false)
    private String ruleOrModelVersion;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String explanation;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String limitations;

    @Column(name = "recommended_action", nullable = false, columnDefinition = "TEXT")
    private String recommendedAction;

    @Column(name = "target_breakdown_json", columnDefinition = "TEXT")
    private String targetBreakdownJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public PredictionItemEntity() {}

    public static PredictionItemEntity create(
        String tenantId, String predictionRunId, String category, String riskLevel, int riskScore,
        BigDecimal probability, String confidenceLevel, boolean insufficientData, int historicalSampleSize,
        Instant dataFreshnessAt, String ruleOrModelVersion, String explanation, String limitations,
        String recommendedAction, String targetBreakdownJson
    ) {
        PredictionItemEntity item = new PredictionItemEntity();
        item.id = UUID.randomUUID().toString();
        item.tenantId = tenantId;
        item.predictionRunId = predictionRunId;
        item.category = category;
        item.riskLevel = riskLevel;
        item.riskScore = Math.max(0, Math.min(100, riskScore));
        item.probability = probability;
        item.confidenceLevel = confidenceLevel;
        item.insufficientData = insufficientData;
        item.historicalSampleSize = Math.max(0, historicalSampleSize);
        item.dataFreshnessAt = dataFreshnessAt;
        item.ruleOrModelVersion = ruleOrModelVersion;
        item.explanation = explanation;
        item.limitations = limitations;
        item.recommendedAction = recommendedAction;
        item.targetBreakdownJson = targetBreakdownJson;
        item.createdAt = Instant.now();
        return item;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getPredictionRunId() { return predictionRunId; }
    public String getCategory() { return category; }
    public String getRiskLevel() { return riskLevel; }
    public int getRiskScore() { return riskScore; }
    public BigDecimal getProbability() { return probability; }
    public String getConfidenceLevel() { return confidenceLevel; }
    public boolean isInsufficientData() { return insufficientData; }
    public int getHistoricalSampleSize() { return historicalSampleSize; }
    public Instant getDataFreshnessAt() { return dataFreshnessAt; }
    public String getRuleOrModelVersion() { return ruleOrModelVersion; }
    public String getExplanation() { return explanation; }
    public String getLimitations() { return limitations; }
    public String getRecommendedAction() { return recommendedAction; }
    public String getTargetBreakdownJson() { return targetBreakdownJson; }
    public Instant getCreatedAt() { return createdAt; }
}
