package com.forge.platform.prediction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditService;
import com.forge.platform.common.RateLimitExceededException;
import com.forge.platform.common.TenantContext;
import com.forge.platform.messaging.RateLimiterService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chronological Held-Out Prediction Evaluation Engine (Milestone 7 / Section 10).
 *
 * Evaluates stored historical predictions strictly against real confirmed post-merge outcomes
 * (defects, CI test failures, deployment failures/rollbacks, confirmed incidents).
 *
 * Truth boundary: NEVER invents or reports accuracy metrics unless sufficient real labelled
 * outcomes exist (MIN_TOTAL_MATCHED_SAMPLES = 5, evaluated on the chronological held-out split).
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
        List<PredictionRunEntity> runs = (repositoryId != null && !repositoryId.isBlank())
            ? runRepository.findByTenantIdAndRepositoryIdOrderByCreatedAtDesc(tenantId, repositoryId)
            : runRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);

        // Deduplicate to latest prediction run per PR review, sorted chronologically ascending
        Map<String, PredictionRunEntity> latestByReview = new LinkedHashMap<>();
        for (PredictionRunEntity run : runs) {
            latestByReview.putIfAbsent(run.getReviewId(), run);
        }
        List<PredictionRunEntity> chronologicalRuns = new ArrayList<>(latestByReview.values());
        chronologicalRuns.sort(Comparator.comparing(PredictionRunEntity::getCreatedAt));

        List<String> runIds = chronologicalRuns.stream().map(PredictionRunEntity::getId).toList();
        Map<String, Map<String, PredictionItemEntity>> itemsByRunAndCategory = new LinkedHashMap<>();
        if (!runIds.isEmpty()) {
            for (PredictionItemEntity item : itemRepository.findByTenantIdAndPredictionRunIdIn(tenantId, runIds)) {
                itemsByRunAndCategory
                    .computeIfAbsent(item.getPredictionRunId(), k -> new LinkedHashMap<>())
                    .put(item.getCategory(), item);
            }
        }

        List<DefectRecordEntity> defects = defectRepository.findByTenantIdOrderByReportedAtDesc(tenantId);
        List<HistoricalTestExecutionEntity> tests = testRepository.findByTenantIdOrderByExecutedAtDesc(tenantId);
        List<DeploymentRecordEntity> deployments = deploymentRepository.findByTenantIdOrderByDeployedAtDesc(tenantId);
        List<IncidentRecordEntity> incidents = incidentRepository.findByTenantIdOrderByOccurredAtDesc(tenantId);

        List<PredictionDtos.ModelEvaluationDto> results = new ArrayList<>();
        results.add(evaluateCategory(tenantId, repositoryId, BugRiskPredictor.CATEGORY, chronologicalRuns, itemsByRunAndCategory,
            run -> defects.stream().anyMatch(d -> d.getRepositoryId().equals(run.getRepositoryId())
                && d.getIntroducedPrNumber() != null && d.getIntroducedPrNumber() == run.getPrNumber()),
            run -> !defects.isEmpty()));

        results.add(evaluateCategory(tenantId, repositoryId, TestFailurePredictor.CATEGORY, chronologicalRuns, itemsByRunAndCategory,
            run -> tests.stream().anyMatch(t -> t.getRepositoryId().equals(run.getRepositoryId())
                && t.getPrNumber() != null && t.getPrNumber() == run.getPrNumber()
                && ("FAILED".equalsIgnoreCase(t.getOutcome()) || "FLAKY".equalsIgnoreCase(t.getOutcome()))),
            run -> tests.stream().anyMatch(t -> t.getRepositoryId().equals(run.getRepositoryId())
                && t.getPrNumber() != null && t.getPrNumber() == run.getPrNumber())));

        results.add(evaluateCategory(tenantId, repositoryId, OperationalRiskPredictor.CATEGORY_CHANGE_FAILURE, chronologicalRuns, itemsByRunAndCategory,
            run -> deployments.stream().anyMatch(d -> d.getRepositoryId().equals(run.getRepositoryId())
                && d.getPrNumber() != null && d.getPrNumber() == run.getPrNumber()
                && ("FAILED".equalsIgnoreCase(d.getOutcome()) || "ROLLED_BACK".equalsIgnoreCase(d.getOutcome()))),
            run -> deployments.stream().anyMatch(d -> d.getRepositoryId().equals(run.getRepositoryId())
                && d.getPrNumber() != null && d.getPrNumber() == run.getPrNumber())));

        results.add(evaluateCategory(tenantId, repositoryId, OperationalRiskPredictor.CATEGORY_INCIDENT, chronologicalRuns, itemsByRunAndCategory,
            run -> incidents.stream().anyMatch(i -> i.getRepositoryId().equals(run.getRepositoryId())
                && i.getRelatedPrNumber() != null && i.getRelatedPrNumber() == run.getPrNumber()),
            run -> !incidents.isEmpty()));

        auditService.recordCurrent("PREDICTION_EVALUATION_RUN", "MODEL_EVALUATION",
            repositoryId != null ? repositoryId : "workspace",
            "Executed chronological held-out evaluation across " + results.size() + " predictive categories");
        return results;
    }

    private PredictionDtos.ModelEvaluationDto evaluateCategory(
        String tenantId,
        String repositoryId,
        String category,
        List<PredictionRunEntity> chronologicalRuns,
        Map<String, Map<String, PredictionItemEntity>> itemsByRun,
        java.util.function.Predicate<PredictionRunEntity> isActualPositive,
        java.util.function.Predicate<PredictionRunEntity> hasObservedLabel
    ) {
        List<LabelledPair> pairs = new ArrayList<>();
        for (PredictionRunEntity run : chronologicalRuns) {
            PredictionItemEntity item = itemsByRun.getOrDefault(run.getId(), Map.of()).get(category);
            if (item == null || !hasObservedLabel.test(run)) continue;
            pairs.add(new LabelledPair(run, item, isActualPositive.test(run)));
        }

        if (pairs.size() < MIN_TOTAL_MATCHED_SAMPLES) {
            String note = "Insufficient labelled historical outcomes for chronological held-out evaluation: found "
                + pairs.size() + " matched PR(s), minimum required is " + MIN_TOTAL_MATCHED_SAMPLES
                + ". Accuracy metrics are intentionally omitted until sufficient confirmed outcomes are ingested.";
            PredictionModelEvaluationEntity saved = evaluationRepository.save(
                PredictionModelEvaluationEntity.create(
                    tenantId, repositoryId, PredictionDtos.ENGINE_VERSION, category,
                    "INSUFFICIENT_LABELLED_DATA", pairs.size(),
                    (int) pairs.stream().filter(LabelledPair::actualPositive).count(),
                    null, null, null, null, null, null,
                    writeJsonSafe(Map.of("evaluated", false, "minimumRequired", MIN_TOTAL_MATCHED_SAMPLES), "{}"),
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

        double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
        double recall = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);
        double f1 = (precision + recall) == 0 ? 0.0 : (2.0 * precision * recall) / (precision + recall);
        double fpr = (fp + tn) == 0 ? 0.0 : (double) fp / (fp + tn);
        double fnr = (fn + tp) == 0 ? 0.0 : (double) fn / (fn + tp);
        double calError = calibrationAbsErrorSum / heldOut.size();

        // Compare against naive always-positive baseline on the exact same held-out slice
        double naivePrecision = (double) positives / heldOut.size();
        double naiveRecall = positives > 0 ? 1.0 : 0.0;
        double naiveF1 = (naivePrecision + naiveRecall) == 0 ? 0.0
            : (2.0 * naivePrecision * naiveRecall) / (naivePrecision + naiveRecall);

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("splitMethod", "CHRONOLOGICAL_HELD_OUT_TAIL");
        comparison.put("totalMatchedPrs", pairs.size());
        comparison.put("heldOutCount", heldOut.size());
        comparison.put("thresholdScore", POSITIVE_SCORE_THRESHOLD);
        comparison.put("confusionMatrix", Map.of("tp", tp, "fp", fp, "tn", tn, "fn", fn));
        comparison.put("naiveAlwaysPositiveF1", round4(naiveF1));
        comparison.put("f1DeltaVsNaive", round4(f1 - naiveF1));

        String notes = "Chronological held-out evaluation on newest " + heldOut.size() + " of " + pairs.size()
            + " matched PRs (" + positives + " confirmed positive outcomes). TP=" + tp + ", FP=" + fp
            + ", TN=" + tn + ", FN=" + fn + ".";

        PredictionModelEvaluationEntity saved = evaluationRepository.save(
            PredictionModelEvaluationEntity.create(
                tenantId, repositoryId, PredictionDtos.ENGINE_VERSION, category,
                "EVALUATED", heldOut.size(), positives,
                round4(precision), round4(recall), round4(f1), round4(fpr), round4(fnr), round4(calError),
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
