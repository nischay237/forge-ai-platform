package com.forge.platform.prediction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prediction_evidence")
public class PredictionEvidenceEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "prediction_item_id", nullable = false)
    private String predictionItemId;

    @Column(name = "signal_source", nullable = false)
    private String signalSource;

    @Column(name = "evidence_type", nullable = false)
    private String evidenceType;

    @Column(name = "target_ref")
    private String targetRef;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false)
    private int weight;

    @Column(name = "historical_count", nullable = false)
    private int historicalCount;

    @Column(name = "observed_at")
    private Instant observedAt;

    public PredictionEvidenceEntity() {}

    public static PredictionEvidenceEntity create(
        String tenantId, String predictionItemId, String signalSource, String evidenceType,
        String targetRef, String summary, int weight, int historicalCount, Instant observedAt
    ) {
        PredictionEvidenceEntity ev = new PredictionEvidenceEntity();
        ev.id = UUID.randomUUID().toString();
        ev.tenantId = tenantId;
        ev.predictionItemId = predictionItemId;
        ev.signalSource = signalSource;
        ev.evidenceType = evidenceType;
        ev.targetRef = targetRef;
        ev.summary = summary;
        ev.weight = weight;
        ev.historicalCount = Math.max(0, historicalCount);
        ev.observedAt = observedAt;
        return ev;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getPredictionItemId() { return predictionItemId; }
    public String getSignalSource() { return signalSource; }
    public String getEvidenceType() { return evidenceType; }
    public String getTargetRef() { return targetRef; }
    public String getSummary() { return summary; }
    public int getWeight() { return weight; }
    public int getHistoricalCount() { return historicalCount; }
    public Instant getObservedAt() { return observedAt; }
}
