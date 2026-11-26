package com.forge.platform.prediction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditService;
import com.forge.platform.common.RateLimitExceededException;
import com.forge.platform.common.TenantContext;
import com.forge.platform.messaging.RateLimiterService;
import com.forge.platform.sse.PipelineEvent;
import com.forge.platform.sse.SseHubService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chronological Held-Out Prediction Evaluation Engine (Milestone 7 / Section 10).
 *
 * Evaluates stored historical predictions strictly against real confirmed post-prediction outcomes
 * (defects, CI test failures, deployment failures/rollbacks, confirmed incidents).
 *
 * Anti-leakage and truth boundaries:
 * - Uses the EARLIEST (pre-outcome) prediction run per PR review so later recomputations cannot leak future outcomes.
 * - Matches only outcomes observed at or after the prediction run's creation timestamp (1s clock-skew tolerance).
 * - Reports null (undefined) instead of misleading 0.0 when class imbalance yields a zero denominator.
 * - NEVER invents or reports accuracy metrics unless sufficient real labelled outcomes exist
 *   (MIN_TOTAL_MATCHED_SAMPLES = 5, evaluated on the chronological held-out tail).
 */
@Service
public class PredictionEvaluationService {

    public static final int MIN_TOTAL_MATCHED_SAMPLES = 5;
    private static final int POSITIVE_SCORE_THRESHOLD = 45;

    private record LabelledPair(
        PredictionRunEntity run,
        PredictionItemEntity item,
        boolean actualPositive
    ) {}

    private final PredictionRunRepository runRepository;
    private final PredictionItemRepository itemRepository;
    private final DefectRecordRepository defectRepository;
    private final HistoricalTestExecutionRepository testRepository;
    private final DeploymentRecordRepository deploymentRepository;
    private final IncidentRecordRepository incidentRepository;
    private final PredictionModelEvaluationRepository evaluationRepository;
    private final RateLimiterService rateLimiterService;
    private final SseHubService sseHubService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PredictionEvaluationService(
        PredictionRunRepository runRepository,
        PredictionItemRepository itemRepository,
        DefectRecordRepository defectRepository,
        HistoricalTestExecutionRepository testRepository,
        DeploymentRecordRepository deploymentRepository,
        IncidentRecordRepository incidentRepository,
        PredictionModelEvaluationRepository evaluationRepository,
        RateLimiterService rateLimiterService,
        SseHubService sseHubService,
        AuditService auditService,
        ObjectMapper objectMapper
    ) {
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.defectRepository = defectRepository;
        this.testRepository = testRepository;
        this.deploymentRepository = deploymentRepository;
        this.incidentRepository = incidentRepository;
        this.evaluationRepository = evaluationRepository;
        this.rateLimiterService = rateLimiterService;
        this.sseHubService = sseHubService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<PredictionDtos.ModelEvaluationDto> listEvaluations() {
        String tenantId = TenantContext.requireTenantId();
        return evaluationRepository.findByTenantIdOrderByEvaluatedAtDesc(tenantId)
            .stream()
            .map(this::toDto)
            .toList();
    }

    @Transactional
    public List<PredictionDtos.ModelEvaluationDto> runChronologicalEvaluation(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        if (!rateLimiterService.allowRequest("pred-eval:" + tenantId, 15, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("Rate limit exceeded for model evaluations. Please retry shortly.");
        }
        String scopeId = (repositoryId != null && !repositoryId.isBlank()) ? repositoryId : "workspace";

        sseHubService.publish(tenantId, PipelineEvent.stage(
            "PREDICTION_EVALUATION", scopeId, "LOADING_PREDICTION_HISTORY",
            "Loading historical pre-outcome prediction runs", "STARTED", 1, 4,
            "Querying stored prediction runs for " + ("workspace".equals(scopeId) ? "all repositories" : "repository " + scopeId),
            Map.of("scope", scopeId)
        ));

        try {
            List<PredictionRunEntity> runs = (repositoryId != null && !repositoryId.isBlank())
                ? runRepository.findByTenantIdAndRepositoryIdOrderByCreatedAtDesc(tenantId, repositoryId)
                : runRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);

            // Deduplicate to EARLIEST (pre-outcome) prediction run per PR review to prevent temporal / future-data leakage
            List<PredictionRunEntity> sortedAsc = new ArrayList<>(runs);
            sortedAsc.sort(Comparator.comparing(PredictionRunEntity::getCreatedAt));
            Map<String, PredictionRunEntity> earliestByReview = new LinkedHashMap<>();
            for (PredictionRunEntity run : sortedAsc) {
                earliestByReview.putIfAbsent(run.getReviewId(), run);
            }
            List<PredictionRunEntity> chronologicalRuns = new ArrayList<>(earliestByReview.values());

            List<String> runIds = chronologicalRuns.stream().map(PredictionRunEntity::getId).toList();
            Map<String, Map<String, PredictionItemEntity>> itemsByRunAndCategory = new LinkedHashMap<>();
            if (!runIds.isEmpty()) {
                for (PredictionItemEntity item : itemRepository.findByTenantIdAndPredictionRunIdIn(tenantId, runIds)) {
                    itemsByRunAndCategory
                        .computeIfAbsent(item.getPredictionRunId(), k -> new LinkedHashMap<>())
                        .put(item.getCategory(), item);
                }
            }

            sseHubService.publish(tenantId, PipelineEvent.stage(
                "PREDICTION_EVALUATION", scopeId, "LOADING_CONFIRMED_OUTCOMES",
                "Loading confirmed defects, tests, deploys & incidents", "IN_PROGRESS", 2, 4,
                "Loaded " + chronologicalRuns.size() + " unique pre-outcome PR prediction run(s); matching against confirmed outcomes",
                Map.of("preOutcomeRuns", chronologicalRuns.size(), "totalStoredRuns", runs.size())
            ));

            List<DefectRecordEntity> defects = defectRepository.findByTenantIdOrderByReportedAtDesc(tenantId);
            List<HistoricalTestExecutionEntity> tests = testRepository.findByTenantIdOrderByExecutedAtDesc(tenantId);
            List<DeploymentRecordEntity> deployments = deploymentRepository.findByTenantIdOrderByDeployedAtDesc(tenantId);
            List<IncidentRecordEntity> incidents = incidentRepository.findByTenantIdOrderByOccurredAtDesc(tenantId);

            sseHubService.publish(tenantId, PipelineEvent.stage(
                "PREDICTION_EVALUATION", scopeId, "EVALUATING_HELD_OUT_SPLITS",
                "Evaluating chronological held-out confusion matrices", "IN_PROGRESS", 3, 4,
                "Scoring chronological held-out splits with temporal anti-leakage checks across 4 categories",
                Map.of(
                    "defects", defects.size(),
                    "testExecutions", tests.size(),
                    "deployments", deployments.size(),
                    "incidents", incidents.size()
                )
            ));

            List<PredictionDtos.ModelEvaluationDto> results = new ArrayList<>();
            results.add(evaluateCategory(tenantId, repositoryId, BugRiskPredictor.CATEGORY, chronologicalRuns, itemsByRunAndCategory,
                run -> defects.stream().anyMatch(d -> d.getRepositoryId().equals(run.getRepositoryId())
                    && d.getIntroducedPrNumber() != null && d.getIntroducedPrNumber() == run.getPrNumber()
                    && isPostPrediction(d.getReportedAt(), run.getCreatedAt())),
                run -> defects.stream().anyMatch(d -> d.getRepositoryId().equals(run.getRepositoryId())
                    && isPostPrediction(d.getReportedAt(), run.getCreatedAt())),
                run -> (int) defects.stream().filter(d -> d.getRepositoryId().equals(run.getRepositoryId())
                    && d.getIntroducedPrNumber() != null && d.getIntroducedPrNumber() == run.getPrNumber()
                    && !isPostPrediction(d.getReportedAt(), run.getCreatedAt())).count()));

            results.add(evaluateCategory(tenantId, repositoryId, TestFailurePredictor.CATEGORY, chronologicalRuns, itemsByRunAndCategory,
                run -> tests.stream().anyMatch(t -> t.getRepositoryId().equals(run.getRepositoryId())
                    && t.getPrNumber() != null && t.getPrNumber() == run.getPrNumber()
                    && isPostPrediction(t.getExecutedAt(), run.getCreatedAt())
                    && ("FAILED".equalsIgnoreCase(t.getOutcome()) || "FLAKY".equalsIgnoreCase(t.getOutcome()))),
                run -> tests.stream().anyMatch(t -> t.getRepositoryId().equals(run.getRepositoryId())
                    && t.getPrNumber() != null && t.getPrNumber() == run.getPrNumber()
                    && isPostPrediction(t.getExecutedAt(), run.getCreatedAt())),
                run -> (int) tests.stream().filter(t -> t.getRepositoryId().equals(run.getRepositoryId())
                    && t.getPrNumber() != null && t.getPrNumber() == run.getPrNumber()
                    && !isPostPrediction(t.getExecutedAt(), run.getCreatedAt())).count()));

            results.add(evaluateCategory(tenantId, repositoryId, OperationalRiskPredictor.CATEGORY_CHANGE_FAILURE, chronologicalRuns, itemsByRunAndCategory,
                run -> deployments.stream().anyMatch(d -> d.getRepositoryId().equals(run.getRepositoryId())
                    && d.getPrNumber() != null && d.getPrNumber() == run.getPrNumber()
                    && isPostPrediction(d.getDeployedAt(), run.getCreatedAt())
                    && ("FAILED".equalsIgnoreCase(d.getOutcome()) || "ROLLED_BACK".equalsIgnoreCase(d.getOutcome()))),
                run -> deployments.stream().anyMatch(d -> d.getRepositoryId().equals(run.getRepositoryId())
                    && d.getPrNumber() != null && d.getPrNumber() == run.getPrNumber()
                    && isPostPrediction(d.getDeployedAt(), run.getCreatedAt())),
                run -> (int) deployments.stream().filter(d -> d.getRepositoryId().equals(run.getRepositoryId())
                    && d.getPrNumber() != null && d.getPrNumber() == run.getPrNumber()
                    && !isPostPrediction(d.getDeployedAt(), run.getCreatedAt())).count()));

            results.add(evaluateCategory(tenantId, repositoryId, OperationalRiskPredictor.CATEGORY_INCIDENT, chronologicalRuns, itemsByRunAndCategory,
                run -> incidents.stream().anyMatch(i -> i.getRepositoryId().equals(run.getRepositoryId())
                    && i.getRelatedPrNumber() != null && i.getRelatedPrNumber() == run.getPrNumber()
                    && isPostPrediction(i.getOccurredAt(), run.getCreatedAt())),
                run -> incidents.stream().anyMatch(i -> i.getRepositoryId().equals(run.getRepositoryId())
                    && isPostPrediction(i.getOccurredAt(), run.getCreatedAt())),
                run -> (int) incidents.stream().filter(i -> i.getRepositoryId().equals(run.getRepositoryId())
                    && i.getRelatedPrNumber() != null && i.getRelatedPrNumber() == run.getPrNumber()
                    && !isPostPrediction(i.getOccurredAt(), run.getCreatedAt())).count()));

            auditService.recordCurrent("PREDICTION_EVALUATION_RUN", "MODEL_EVALUATION",
                scopeId,
                "Executed chronological held-out evaluation across " + results.size() + " predictive categories");

            long evaluatedCount = results.stream().filter(r -> "EVALUATED".equals(r.status())).count();
            sseHubService.publish(tenantId, PipelineEvent.stage(
                "PREDICTION_EVALUATION", scopeId, "EVALUATION_COMPLETED",
                "Held-out evaluation complete", "COMPLETED", 4, 4,
                "Evaluated " + results.size() + " predictive categories (" + evaluatedCount + " with sufficient labelled data)",
                Map.of(
                    "categories", results.size(),
                    "evaluatedCategories", evaluatedCount,
                    "insufficientDataCategories", results.size() - evaluatedCount
                )
            ));

            return results;
        } catch (RuntimeException ex) {
            sseHubService.publish(tenantId, PipelineEvent.stage(
                "PREDICTION_EVALUATION", scopeId, "FAILED",
                "Evaluation failed", "FAILED", 4, 4,
                "Chronological evaluation encountered an error: " + (ex.getMessage() != null ? ex.getMessage() : "unexpected error"),
                Map.of("error", ex.getMessage() != null ? ex.getMessage() : "unexpected error")
            ));
            throw ex;
        }
    }

    private boolean isPostPrediction(Instant outcomeAt, Instant predictedAt) {
        if (outcomeAt == null || predictedAt == null) return false;
        // Require outcomes to be observed at or after the initial prediction (1s tolerance for sub-second clock jitter)
        return !outcomeAt.isBefore(predictedAt.minusSeconds(1));
    }

    private PredictionDtos.ModelEvaluationDto evaluateCategory(
        String tenantId,
        String repositoryId,
        String category,
        List<PredictionRunEntity> chronologicalRuns,
        Map<String, Map<String, PredictionItemEntity>> itemsByRun,
        java.util.function.Predicate<PredictionRunEntity> isActualPositive,
        java.util.function.Predicate<PredictionRunEntity> hasObservedLabel,
        java.util.function.ToIntFunction<PredictionRunEntity> countExcludedLeakage
    ) {
        List<LabelledPair> pairs = new ArrayList<>();
        int excludedLeakageOutcomes = 0;
        for (PredictionRunEntity run : chronologicalRuns) {
            excludedLeakageOutcomes += countExcludedLeakage.applyAsInt(run);
            PredictionItemEntity item = itemsByRun.getOrDefault(run.getId(), Map.of()).get(category);
            if (item == null || !hasObservedLabel.test(run)) continue;
            pairs.add(new LabelledPair(run, item, isActualPositive.test(run)));
        }

        if (pairs.size() < MIN_TOTAL_MATCHED_SAMPLES) {
            String note = "Insufficient post-prediction labelled outcomes for chronological held-out evaluation: found "
                + pairs.size() + " matched PR(s), minimum required is " + MIN_TOTAL_MATCHED_SAMPLES
                + (excludedLeakageOutcomes > 0 ? " (" + excludedLeakageOutcomes + " pre-prediction outcome(s) excluded to prevent temporal leakage)." : ".")
                + " Accuracy metrics are intentionally omitted until sufficient confirmed outcomes are ingested.";
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("evaluated", false);
            meta.put("minimumRequired", MIN_TOTAL_MATCHED_SAMPLES);
            meta.put("excludedPrePredictionOutcomes", excludedLeakageOutcomes);
            PredictionModelEvaluationEntity saved = evaluationRepository.save(
                PredictionModelEvaluationEntity.create(
                    tenantId, repositoryId, PredictionDtos.ENGINE_VERSION, category,
                    "INSUFFICIENT_LABELLED_DATA", pairs.size(),
                    (int) pairs.stream().filter(LabelledPair::actualPositive).count(),
                    null, null, null, null, null, null,
                    writeJsonSafe(meta, "{}"),
                    note
                )
            );
            return toDto(saved);
        }

        // Chronological held-out split: train/warmup = oldest 60%, held-out validation = newest 40% (min 3)
        int heldOutSize = Math.max(3, (int) Math.ceil(pairs.size() * 0.4));
        int splitIdx = Math.max(0, pairs.size() - heldOutSize);
        List<LabelledPair> heldOut = pairs.subList(splitIdx, pairs.size());

        int tp = 0, fp = 0, tn = 0, fn = 0;
        double calibrationAbsErrorSum = 0.0;
        int positives = 0;

        for (LabelledPair pair : heldOut) {
            boolean predictedPos = pair.item().getRiskScore() >= POSITIVE_SCORE_THRESHOLD;
            boolean actualPos = pair.actualPositive();
            if (actualPos) positives++;
            if (predictedPos && actualPos) tp++;
            else if (predictedPos) fp++;
            else if (actualPos) fn++;
            else tn++;

            double normalizedScore = pair.item().getRiskScore() / 100.0;
            calibrationAbsErrorSum += Math.abs(normalizedScore - (actualPos ? 1.0 : 0.0));
        }

        // Return NULL when class imbalance or zero-prediction causes a zero denominator (never misleading 0.0)
        BigDecimal precision = (tp + fp) == 0 ? null : round4((double) tp / (tp + fp));
        BigDecimal recall = (tp + fn) == 0 ? null : round4((double) tp / (tp + fn));
        BigDecimal f1 = (precision == null || recall == null
            || (precision.doubleValue() + recall.doubleValue()) == 0.0)
            ? null
            : round4((2.0 * precision.doubleValue() * recall.doubleValue())
                / (precision.doubleValue() + recall.doubleValue()));
        BigDecimal fpr = (fp + tn) == 0 ? null : round4((double) fp / (fp + tn));
        BigDecimal fnr = (fn + tp) == 0 ? null : round4((double) fn / (fn + tp));
        BigDecimal calError = round4(calibrationAbsErrorSum / heldOut.size());

        boolean singleClassImbalance = (positives == 0 || positives == heldOut.size());
        Double naiveF1 = null;
        if (positives > 0) {
            double naivePrecision = (double) positives / heldOut.size();
            naiveF1 = (2.0 * naivePrecision * 1.0) / (naivePrecision + 1.0);
        }

        Instant periodStart = heldOut.get(0).run().getCreatedAt();
        Instant periodEnd = heldOut.get(heldOut.size() - 1).run().getCreatedAt();

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("splitMethod", "CHRONOLOGICAL_HELD_OUT_TAIL_PRE_OUTCOME");
        comparison.put("totalMatchedPrs", pairs.size());
        comparison.put("trainWarmupCount", splitIdx);
        comparison.put("heldOutCount", heldOut.size());
        comparison.put("evaluationPeriodStart", periodStart.toString());
        comparison.put("evaluationPeriodEnd", periodEnd.toString());
        comparison.put("excludedPrePredictionOutcomes", excludedLeakageOutcomes);
        comparison.put("thresholdScore", POSITIVE_SCORE_THRESHOLD);
        comparison.put("confusionMatrix", Map.of("tp", tp, "fp", fp, "tn", tn, "fn", fn));
        comparison.put("classImbalanceWarning", singleClassImbalance
            ? "Held-out window contains only one outcome class; undefined ratios are reported as null."
            : null);
        comparison.put("naiveAlwaysPositiveF1", naiveF1 != null ? round4(naiveF1) : null);
        comparison.put("f1DeltaVsNaive", (f1 != null && naiveF1 != null) ? round4(f1.doubleValue() - naiveF1) : null);

        String notes = "Chronological held-out evaluation on newest " + heldOut.size() + " of " + pairs.size()
            + " matched pre-outcome PRs (" + positives + " confirmed positive outcomes, period "
            + periodStart + " to " + periodEnd + "). TP=" + tp + ", FP=" + fp
            + ", TN=" + tn + ", FN=" + fn + "."
            + (singleClassImbalance ? " Single-class held-out window: undefined precision/recall/FPR/FNR omitted." : "");

        PredictionModelEvaluationEntity saved = evaluationRepository.save(
            PredictionModelEvaluationEntity.create(
                tenantId, repositoryId, PredictionDtos.ENGINE_VERSION, category,
                "EVALUATED", heldOut.size(), positives,
                precision, recall, f1, fpr, fnr, calError,
                writeJsonSafe(comparison, "{}"), notes
            )
        );
        return toDto(saved);
    }

    PredictionDtos.ModelEvaluationDto toDto(PredictionModelEvaluationEntity e) {
        Map<String, Object> comp = Map.of();
        if (e.getBaselineComparisonJson() != null && !e.getBaselineComparisonJson().isBlank()) {
            try {
                comp = objectMapper.readValue(e.getBaselineComparisonJson(), new TypeReference<>() {});
            } catch (Exception ignored) {
                comp = Map.of();
            }
        }
        return new PredictionDtos.ModelEvaluationDto(
            e.getId(),
            e.getRepositoryId(),
            e.getEngineVersion(),
            e.getCategory(),
            e.getStatus(),
            e.getSampleSize(),
            e.getPositiveOutcomes(),
            e.getPrecisionScore(),
            e.getRecallScore(),
            e.getF1Score(),
            e.getFalsePositiveRate(),
            e.getFalseNegativeRate(),
            e.getCalibrationError(),
            comp,
            e.getNotes(),
            e.getEvaluatedAt()
        );
    }

    private BigDecimal round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private String writeJsonSafe(Object obj, String fallback) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            return fallback;
        }
    }
}
