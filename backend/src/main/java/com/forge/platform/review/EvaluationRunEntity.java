package com.forge.platform.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evaluation_runs")
public class EvaluationRunEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "model_used", nullable = false)
    private String modelUsed;

    @Column(name = "dataset_size", nullable = false)
    private int datasetSize;

    @Column(name = "true_positives", nullable = false)
    private int truePositives;

    @Column(name = "false_positives", nullable = false)
    private int falsePositives;

    @Column(name = "false_negatives", nullable = false)
    private int falseNegatives;

    @Column(name = "precision_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal precisionScore;

    @Column(name = "recall_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal recallScore;

    @Column(name = "f1_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal f1Score;

    @Column(name = "false_positive_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal falsePositiveRate;

    @Column(name = "avg_latency_ms", nullable = false)
    private long avgLatencyMs;

    @Column(name = "case_results_json", nullable = false, columnDefinition = "TEXT")
    private String caseResultsJson;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    public EvaluationRunEntity() {}

    public static EvaluationRunEntity create(
        String tenantId, String promptVersion, String modelUsed,
        int datasetSize, int tp, int fp, int fn,
        double precision, double recall, double f1, double fpr,
        long avgLatencyMs, String caseResultsJson
    ) {
        EvaluationRunEntity e = new EvaluationRunEntity();
        e.id = UUID.randomUUID().toString();
        e.tenantId = tenantId;
        e.promptVersion = promptVersion;
        e.modelUsed = modelUsed;
        e.datasetSize = datasetSize;
        e.truePositives = tp;
        e.falsePositives = fp;
        e.falseNegatives = fn;
        e.precisionScore = BigDecimal.valueOf(precision);
        e.recallScore = BigDecimal.valueOf(recall);
        e.f1Score = BigDecimal.valueOf(f1);
        e.falsePositiveRate = BigDecimal.valueOf(fpr);
        e.avgLatencyMs = avgLatencyMs;
        e.caseResultsJson = caseResultsJson;
        e.executedAt = Instant.now();
        return e;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getPromptVersion() { return promptVersion; }
    public String getModelUsed() { return modelUsed; }
    public int getDatasetSize() { return datasetSize; }
    public int getTruePositives() { return truePositives; }
    public int getFalsePositives() { return falsePositives; }
    public int getFalseNegatives() { return falseNegatives; }
    public BigDecimal getPrecisionScore() { return precisionScore; }
    public BigDecimal getRecallScore() { return recallScore; }
    public BigDecimal getF1Score() { return f1Score; }
    public BigDecimal getFalsePositiveRate() { return falsePositiveRate; }
    public long getAvgLatencyMs() { return avgLatencyMs; }
    public String getCaseResultsJson() { return caseResultsJson; }
    public Instant getExecutedAt() { return executedAt; }
}
