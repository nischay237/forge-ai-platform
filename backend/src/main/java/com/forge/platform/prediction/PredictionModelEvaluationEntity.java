package com.forge.platform.prediction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prediction_model_evaluations")
public class PredictionModelEvaluationEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id")
    private String repositoryId;

    @Column(name = "engine_version", nullable = false)
    private String engineVersion;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String status;

    @Column(name = "sample_size", nullable = false)
    private int sampleSize;

    @Column(name = "positive_outcomes", nullable = false)
    private int positiveOutcomes;

    @Column(name = "precision_score", precision = 5, scale = 4)
    private BigDecimal precisionScore;

    @Column(name = "recall_score", precision = 5, scale = 4)
    private BigDecimal recallScore;

    @Column(name = "f1_score", precision = 5, scale = 4)
    private BigDecimal f1Score;

    @Column(name = "false_positive_rate", precision = 5, scale = 4)
    private BigDecimal falsePositiveRate;

    @Column(name = "false_negative_rate", precision = 5, scale = 4)
    private BigDecimal falseNegativeRate;

    @Column(name = "calibration_error", precision = 5, scale = 4)
    private BigDecimal calibrationError;

    @Column(name = "baseline_comparison_json", columnDefinition = "TEXT")
    private String baselineComparisonJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String notes;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    public PredictionModelEvaluationEntity() {}

    public static PredictionModelEvaluationEntity create(
        String tenantId, String repositoryId, String engineVersion, String category, String status,
        int sampleSize, int positiveOutcomes, BigDecimal precisionScore, BigDecimal recallScore,
        BigDecimal f1Score, BigDecimal falsePositiveRate, BigDecimal falseNegativeRate,
        BigDecimal calibrationError, String baselineComparisonJson, String notes
    ) {
        PredictionModelEvaluationEntity e = new PredictionModelEvaluationEntity();
        e.id = UUID.randomUUID().toString();
        e.tenantId = tenantId;
        e.repositoryId = repositoryId;
        e.engineVersion = engineVersion;
        e.category = category;
        e.status = status;
        e.sampleSize = Math.max(0, sampleSize);
        e.positiveOutcomes = Math.max(0, positiveOutcomes);
        e.precisionScore = precisionScore;
        e.recallScore = recallScore;
        e.f1Score = f1Score;
        e.falsePositiveRate = falsePositiveRate;
        e.falseNegativeRate = falseNegativeRate;
        e.calibrationError = calibrationError;
        e.baselineComparisonJson = baselineComparisonJson;
        e.notes = notes;
        e.evaluatedAt = Instant.now();
        return e;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getEngineVersion() { return engineVersion; }
    public String getCategory() { return category; }
    public String getStatus() { return status; }
    public int getSampleSize() { return sampleSize; }
    public int getPositiveOutcomes() { return positiveOutcomes; }
    public BigDecimal getPrecisionScore() { return precisionScore; }
    public BigDecimal getRecallScore() { return recallScore; }
    public BigDecimal getF1Score() { return f1Score; }
    public BigDecimal getFalsePositiveRate() { return falsePositiveRate; }
    public BigDecimal getFalseNegativeRate() { return falseNegativeRate; }
    public BigDecimal getCalibrationError() { return calibrationError; }
    public String getBaselineComparisonJson() { return baselineComparisonJson; }
    public String getNotes() { return notes; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
}
