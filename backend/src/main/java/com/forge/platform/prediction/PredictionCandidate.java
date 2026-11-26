package com.forge.platform.prediction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Internal structured output produced by each specialized predictor before confidence
 * normalization, deduplicated risk aggregation, and database persistence.
 */
public final class PredictionCandidate {

    private PredictionCandidate() {}

    public record EvidenceDraft(
        String signalSource,
        String evidenceType,
        String targetRef,
        String summary,
        int weight,
        int historicalCount,
        Instant observedAt,
        String deduplicationKey
    ) {}

    public record CategoryPredictionDraft(
        String category,
        int rawScore,
        String riskLevel,
        BigDecimal probability,
        String confidenceLevel,
        boolean insufficientData,
        int historicalSampleSize,
        Instant dataFreshnessAt,
        String ruleOrModelVersion,
        String explanation,
        String limitations,
        String recommendedAction,
        List<PredictionDtos.TargetRiskBreakdown> targetBreakdowns,
        List<EvidenceDraft> evidence
    ) {}
}
