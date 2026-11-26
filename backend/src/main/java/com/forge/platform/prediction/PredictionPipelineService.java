package com.forge.platform.prediction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditService;
import com.forge.platform.common.RateLimitExceededException;
import com.forge.platform.common.ResourceNotFoundException;
import com.forge.platform.common.TenantContext;
import com.forge.platform.messaging.DomainEventHandler;
import com.forge.platform.messaging.ForgeCacheService;
import com.forge.platform.messaging.ForgeTopics;
import com.forge.platform.messaging.IdempotencyService;
import com.forge.platform.messaging.RateLimiterService;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.RepositoryJpaRepository;
import com.forge.platform.review.PullRequestReviewEntity;
import com.forge.platform.review.PullRequestReviewRepository;
import com.forge.platform.sse.PipelineEvent;
import com.forge.platform.sse.SseHubService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the end-to-end Predictive Engineering Intelligence pipeline (Milestone 7):
 *   - Consumes async `forge.pr-prediction.requested` Outbox/Kafka events idempotently without blocking PR reviews
 *   - Persists immutable versioned prediction runs, per-category predictions, and linked evidence rows
 *   - Reuses cached runs when a PR commit SHA has not changed (avoids redundant re-computation)
 *   - Serves tenant-scoped query views for PR predictions, evidence breakdown, test risk vs actual CI results,
 *     historical context, and workspace overview (cached via ForgeCacheService).
 */
@Service
public class PredictionPipelineService implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PredictionPipelineService.class);

    private final PullRequestReviewRepository reviewRepository;
    private final RepositoryJpaRepository repositoryJpaRepository;
    private final PrFeatureExtractor featureExtractor;
    private final BugRiskPredictor bugRiskPredictor;
    private final TestFailurePredictor testFailurePredictor;
    private final OperationalRiskPredictor operationalRiskPredictor;
    private final PerformanceRegressionAnalyzer performanceRegressionAnalyzer;
    private final EngineeringRiskAggregator riskAggregator;
    private final PredictionRunRepository runRepository;
    private final PredictionItemRepository itemRepository;
    private final PredictionEvidenceRepository evidenceRepository;
    private final HistoricalTestExecutionRepository testExecutionRepository;
    private final DeploymentRecordRepository deploymentRecordRepository;
    private final IncidentRecordRepository incidentRecordRepository;
    private final DefectRecordRepository defectRecordRepository;
    private final PredictionModelEvaluationRepository evaluationRepository;
    private final PredictionEvaluationService evaluationService;
    private final IdempotencyService idempotencyService;
    private final ForgeCacheService cacheService;
    private final RateLimiterService rateLimiterService;
    private final SseHubService sseHubService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Counter predictionsCompletedCounter;
    private final Timer predictionDurationTimer;

    public PredictionPipelineService(
        PullRequestReviewRepository reviewRepository,
        RepositoryJpaRepository repositoryJpaRepository,
        PrFeatureExtractor featureExtractor,
        BugRiskPredictor bugRiskPredictor,
        TestFailurePredictor testFailurePredictor,
        OperationalRiskPredictor operationalRiskPredictor,
        PerformanceRegressionAnalyzer performanceRegressionAnalyzer,
        EngineeringRiskAggregator riskAggregator,
        PredictionRunRepository runRepository,
        PredictionItemRepository itemRepository,
        PredictionEvidenceRepository evidenceRepository,
        HistoricalTestExecutionRepository testExecutionRepository,
        DeploymentRecordRepository deploymentRecordRepository,
        IncidentRecordRepository incidentRecordRepository,
        DefectRecordRepository defectRecordRepository,
        PredictionModelEvaluationRepository evaluationRepository,
        PredictionEvaluationService evaluationService,
        IdempotencyService idempotencyService,
        ForgeCacheService cacheService,
        RateLimiterService rateLimiterService,
        SseHubService sseHubService,
        AuditService auditService,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        MeterRegistry meterRegistry
    ) {
        this.reviewRepository = reviewRepository;
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.featureExtractor = featureExtractor;
        this.bugRiskPredictor = bugRiskPredictor;
        this.testFailurePredictor = testFailurePredictor;
        this.operationalRiskPredictor = operationalRiskPredictor;
        this.performanceRegressionAnalyzer = performanceRegressionAnalyzer;
        this.riskAggregator = riskAggregator;
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.evidenceRepository = evidenceRepository;
        this.testExecutionRepository = testExecutionRepository;
        this.deploymentRecordRepository = deploymentRecordRepository;
        this.incidentRecordRepository = incidentRecordRepository;
        this.defectRecordRepository = defectRecordRepository;
        this.evaluationRepository = evaluationRepository;
        this.evaluationService = evaluationService;
        this.idempotencyService = idempotencyService;
        this.cacheService = cacheService;
        this.rateLimiterService = rateLimiterService;
        this.sseHubService = sseHubService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.predictionsCompletedCounter = Counter.builder("forge.predictions.completed").register(meterRegistry);
        this.predictionDurationTimer = Timer.builder("forge.predictions.duration").register(meterRegistry);
    }

    @Override
    public boolean supports(String topic) {
        return ForgeTopics.PR_PREDICTION_REQUESTED.equals(topic);
    }

    @Override
    public void handle(String eventId, String tenantId, String payload) {
        String claimKey = "pr-prediction:" + eventId;
        if (!idempotencyService.tryClaimEvent(claimKey, tenantId, "PR_PREDICTION_REQUESTED")) {
            log.info("Skipping duplicate PR prediction event {}", eventId);
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            String reviewId = node.path("reviewId").asText("");
            transactionTemplate.executeWithoutResult(status -> computeAndPersist(tenantId, reviewId, true, false));
            idempotencyService.markCompleted(claimKey);
        } catch (Exception ex) {
            idempotencyService.releaseClaim(claimKey);
            log.error("Async PR prediction worker failed for event {}: {}", eventId, ex.getMessage(), ex);
            throw new IllegalStateException("PR prediction pipeline failed for event " + eventId, ex);
        }
    }

    @Transactional
    public PredictionDtos.PrPredictionResponse runPrediction(String reviewId) {
        String tenantId = TenantContext.requireTenantId();
        if (!rateLimiterService.allowRequest("pred-run:" + tenantId, 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("Rate limit exceeded for prediction runs. Please retry shortly.");
        }
        PredictionRunEntity run = computeAndPersist(tenantId, reviewId, true, true);
        return buildPrPredictionResponse(tenantId, run);
    }

    @Transactional(readOnly = true)
    public List<PredictionDtos.PredictionRunHistoryItem> listPredictionRuns(String reviewId) {
        String tenantId = TenantContext.requireTenantId();
        requireReview(tenantId, reviewId);
        return runRepository.findByTenantIdAndReviewIdOrderByCreatedAtDesc(tenantId, reviewId)
            .stream()
            .map(r -> new PredictionDtos.PredictionRunHistoryItem(
                r.getId(), r.getCommitSha(), r.getEngineVersion(), r.getModelType(),
                r.getOverallEngineeringRiskScore(), r.getOverallRiskLevel(),
                r.getHistoricalSampleSize(), r.getCreatedAt()
            ))
            .toList();
    }

    @Transactional
    public PredictionDtos.PrPredictionResponse getOrComputePrediction(String reviewId) {
        String tenantId = TenantContext.requireTenantId();
        PullRequestReviewEntity review = requireReview(tenantId, reviewId);
        Optional<PredictionRunEntity> existing = runRepository
            .findFirstByTenantIdAndReviewIdOrderByCreatedAtDesc(tenantId, reviewId);
        if (existing.isPresent() && existing.get().getCommitSha().equals(review.getCommitSha())) {
            return buildPrPredictionResponse(tenantId, existing.get());
        }
        PredictionRunEntity created = computeAndPersist(tenantId, reviewId, false, false);
        return buildPrPredictionResponse(tenantId, created);
    }

    @Transactional
    public PredictionRunEntity computeAndPersist(
        String tenantId, String reviewId, boolean emitLiveProgress, boolean forceRecompute
    ) {
        PullRequestReviewEntity review = requireReview(tenantId, reviewId);
        RepositoryEntity repo = repositoryJpaRepository.findByIdAndTenantId(review.getRepositoryId(), tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Repository not found for review"));

        if (!forceRecompute) {
            Optional<PredictionRunEntity> existing = runRepository
                .findFirstByTenantIdAndReviewIdOrderByCreatedAtDesc(tenantId, reviewId);
            if (existing.isPresent()
                && existing.get().getCommitSha().equals(review.getCommitSha())
                && existing.get().getEngineVersion().equals(PredictionDtos.ENGINE_VERSION)) {
                return existing.get();
            }
        }

        long startNanos = System.nanoTime();
        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.of(
                "PR_PREDICTION", reviewId, "PREDICTIVE_FEATURE_EXTRACTION", 35,
                "Extracting PR diff, AST complexity, blast radius & historical engineering features"
            ));
        }

        PrFeatureExtractor.PrFeatureVector fv = featureExtractor.extract(tenantId, repo, review);

        PredictionCandidate.CategoryPredictionDraft bugDraft = bugRiskPredictor.predict(fv);
        TestFailurePredictor.TestFailurePredictionResult testResult = testFailurePredictor.predict(fv);
        PredictionCandidate.CategoryPredictionDraft incidentDraft = operationalRiskPredictor.predictIncidentRisk(fv);
        PredictionCandidate.CategoryPredictionDraft changeFailureDraft = operationalRiskPredictor.predictChangeFailureRisk(fv);
        PredictionCandidate.CategoryPredictionDraft perfDraft = performanceRegressionAnalyzer.analyze(fv);

        EngineeringRiskAggregator.AggregatedEngineeringRisk aggregated = riskAggregator.aggregate(
            fv, bugDraft, testResult.categoryDraft(), incidentDraft, changeFailureDraft, perfDraft
        );

        Map<String, Object> featureSummary = fv.toSummaryMap();
        featureSummary.put("predictedTestsAtRisk", testResult.predictedTests());

        PredictionRunEntity run = runRepository.save(PredictionRunEntity.create(
            tenantId,
            repo.getId(),
            review.getId(),
            review.getPrNumber(),
            review.getCommitSha(),
            PredictionDtos.ENGINE_VERSION,
            PredictionDtos.MODEL_TYPE_DETERMINISTIC,
            aggregated.overallScore(),
            aggregated.overallRiskLevel(),
            writeJsonSafe(aggregated.signalChannels(), "[]"),
            writeJsonSafe(featureSummary, "{}"),
            fv.totalHistoricalSampleSize(),
            fv.dataFreshnessAt(),
            fv.dataAvailabilitySummary()
        ));

        List<PredictionCandidate.CategoryPredictionDraft> allDrafts = List.of(
            bugDraft, testResult.categoryDraft(), incidentDraft, changeFailureDraft, perfDraft
        );
        for (PredictionCandidate.CategoryPredictionDraft draft : allDrafts) {
            PredictionItemEntity savedItem = itemRepository.save(PredictionItemEntity.create(
                tenantId,
                run.getId(),
                draft.category(),
                draft.riskLevel(),
                draft.rawScore(),
                draft.probability(),
                draft.confidenceLevel(),
                draft.insufficientData(),
                draft.historicalSampleSize(),
                draft.dataFreshnessAt(),
                draft.ruleOrModelVersion(),
                draft.explanation(),
                draft.limitations(),
                draft.recommendedAction(),
                writeJsonSafe(draft.targetBreakdowns(), "[]")
            ));
            for (PredictionCandidate.EvidenceDraft ev : draft.evidence()) {
                evidenceRepository.save(PredictionEvidenceEntity.create(
                    tenantId,
                    savedItem.getId(),
                    ev.signalSource(),
                    ev.evidenceType(),
                    ev.targetRef(),
                    ev.summary(),
                    ev.weight(),
                    ev.historicalCount(),
                    ev.observedAt()
                ));
            }
        }

        cacheService.evict(HistoricalDataIngestionService.OVERVIEW_CACHE_PREFIX + tenantId);
        predictionDurationTimer.record(Duration.ofNanos(Math.max(1L, System.nanoTime() - startNanos)));
        predictionsCompletedCounter.increment();

        auditService.record(tenantId, "prediction-worker@forge.dev", "SYSTEM",
            "PR_PREDICTION_COMPLETED", "PREDICTION_RUN", run.getId(),
            "Generated predictive risk baseline for PR #" + review.getPrNumber()
                + " (overall=" + aggregated.overallScore() + "/100 " + aggregated.overallRiskLevel()
                + ", historicalSamples=" + fv.totalHistoricalSampleSize() + ")",
            "prediction");

        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.of(
                "PR_PREDICTION", reviewId, "PREDICTION_COMPLETED", 100,
                "Predictive baseline ready: " + aggregated.overallRiskLevel() + " (" + aggregated.overallScore() + "/100)"
            ));
        }

        return run;
    }

    @Transactional
    public PredictionDtos.PredictionEvidenceResponse getPredictionEvidence(String reviewId) {
        String tenantId = TenantContext.requireTenantId();
        PredictionDtos.PrPredictionResponse pred = getOrComputePrediction(reviewId);

        List<PredictionDtos.TargetRiskBreakdown> byFile = new ArrayList<>();
        List<PredictionDtos.TargetRiskBreakdown> byClass = new ArrayList<>();
        List<PredictionDtos.TargetRiskBreakdown> byMethod = new ArrayList<>();
        List<PredictionDtos.TargetRiskBreakdown> byModule = new ArrayList<>();
        List<PredictionDtos.TargetRiskBreakdown> byService = new ArrayList<>();
        List<PredictionDtos.EvidenceDto> allEvidence = new ArrayList<>();

        for (PredictionDtos.PredictionItemDto item : pred.predictions()) {
            allEvidence.addAll(item.evidence());
            for (PredictionDtos.TargetRiskBreakdown tb : item.targetBreakdowns()) {
                switch (tb.scope()) {
                    case "FILE" -> byFile.add(tb);
                    case "CLASS" -> byClass.add(tb);
                    case "METHOD" -> byMethod.add(tb);
                    case "MODULE" -> byModule.add(tb);
                    case "SERVICE" -> byService.add(tb);
                    default -> byFile.add(tb);
                }
            }
        }

        return new PredictionDtos.PredictionEvidenceResponse(
            pred.predictionRunId(),
            pred.reviewId(),
            pred.engineVersion(),
            dedupTargets(byFile),
            dedupTargets(byClass),
            dedupTargets(byMethod),
            dedupTargets(byModule),
            dedupTargets(byService),
            allEvidence
        );
    }

    @Transactional
    public PredictionDtos.PredictionTestsResponse getPredictionTests(String reviewId) {
        String tenantId = TenantContext.requireTenantId();
        PullRequestReviewEntity review = requireReview(tenantId, reviewId);
        PredictionDtos.PrPredictionResponse pred = getOrComputePrediction(reviewId);

        List<PredictionDtos.PredictedTestRiskItem> predicted = List.of();
        Object rawPredicted = pred.featureSummary().get("predictedTestsAtRisk");
        if (rawPredicted != null) {
            try {
                predicted = objectMapper.convertValue(rawPredicted, new TypeReference<>() {});
            } catch (Exception ignored) {
                predicted = List.of();
            }
        }

        List<PredictionDtos.ActualTestExecutionItem> actualResults = testExecutionRepository
            .findByTenantIdAndRepositoryIdAndPrNumberOrderByExecutedAtDesc(tenantId, review.getRepositoryId(), review.getPrNumber())
            .stream()
            .map(this::toActualTestDto)
            .toList();

        String separationNotice = actualResults.isEmpty()
            ? "Predicted tests at risk are static/historical forecasts and have NOT been executed by FORGE. "
                + "No actual CI test execution records have been ingested for PR #" + review.getPrNumber() + " yet."
            : "Predicted tests at risk (left) are pre-execution forecasts; actual CI test results (right) reflect "
                + actualResults.size() + " confirmed execution record(s) for PR #" + review.getPrNumber() + ".";

        return new PredictionDtos.PredictionTestsResponse(
            pred.predictionRunId(),
            review.getId(),
            review.getPrNumber(),
            separationNotice,
            predicted,
            actualResults,
            !actualResults.isEmpty()
        );
    }

    @Transactional(readOnly = true)
    public PredictionDtos.HistoricalContextResponse getPredictionHistory(String reviewId) {
        String tenantId = TenantContext.requireTenantId();
        PullRequestReviewEntity review = requireReview(tenantId, reviewId);
        RepositoryEntity repo = repositoryJpaRepository.findByIdAndTenantId(review.getRepositoryId(), tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));

        PrFeatureExtractor.PrFeatureVector fv = featureExtractor.extract(tenantId, repo, review);

        List<PredictionDtos.DefectRecordDto> defects = fv.matchingDefects().stream()
            .map(d -> new PredictionDtos.DefectRecordDto(
                d.getId(), d.getRepositoryId(), d.getTitle(), d.getSeverity(), d.getModuleName(),
                featureExtractor.parseStringList(d.getFilePathsJson()), d.getIntroducedPrNumber(),
                d.getStatus(), d.getReportedAt()
            )).toList();

        List<PredictionDtos.ActualTestExecutionItem> tests = fv.repoTestExecutions().stream()
            .limit(25)
            .map(this::toActualTestDto)
            .toList();

        List<PredictionDtos.DeploymentRecordDto> deployments = fv.serviceDeployments().stream()
            .limit(20)
            .map(d -> new PredictionDtos.DeploymentRecordDto(
                d.getId(), d.getRepositoryId(), d.getServiceName(), d.getEnvironment(),
                d.getPrNumber(), d.getCommitSha(), d.getOutcome(), d.getRollbackOfDeploymentId(),
                d.isChangedConfig(), d.getSummary(), d.getDeployedAt()
            )).toList();

        List<PredictionDtos.IncidentRecordDto> incidents = fv.matchingIncidents().stream()
            .limit(20)
            .map(this::toIncidentDto)
            .toList();

        int totalMatching = defects.size() + tests.size() + deployments.size() + incidents.size();
        boolean limited = totalMatching < 3;

        return new PredictionDtos.HistoricalContextResponse(
            review.getId(),
            repo.getId(),
            repo.getFullName(),
            limited,
            fv.dataAvailabilitySummary(),
            totalMatching,
            defects,
            tests,
            deployments,
            incidents
        );
    }

    @Transactional(readOnly = true)
    public PredictionDtos.PredictiveOverviewResponse getOverview() {
        String tenantId = TenantContext.requireTenantId();
        String cacheKey = HistoricalDataIngestionService.OVERVIEW_CACHE_PREFIX + tenantId;
        Optional<String> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get(), PredictionDtos.PredictiveOverviewResponse.class);
            } catch (Exception ignored) {
                // fall through and recompute
            }
        }

        List<RepositoryEntity> repos = repositoryJpaRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        Map<String, String> repoNames = new HashMap<>();
        repos.forEach(r -> repoNames.put(r.getId(), r.getFullName()));

        List<PullRequestReviewEntity> reviews = reviewRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        Map<String, PullRequestReviewEntity> reviewById = new HashMap<>();
        reviews.forEach(r -> reviewById.put(r.getId(), r));

        List<PredictionRunEntity> allRuns = runRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        Map<String, PredictionRunEntity> latestByReview = new LinkedHashMap<>();
        for (PredictionRunEntity run : allRuns) {
            latestByReview.putIfAbsent(run.getReviewId(), run);
        }
        List<PredictionRunEntity> latestRuns = new ArrayList<>(latestByReview.values());
        List<String> runIds = latestRuns.stream().map(PredictionRunEntity::getId).toList();
        Map<String, Map<String, PredictionItemEntity>> itemsByRun = new HashMap<>();
        if (!runIds.isEmpty()) {
            for (PredictionItemEntity item : itemRepository.findByTenantIdAndPredictionRunIdIn(tenantId, runIds)) {
                itemsByRun.computeIfAbsent(item.getPredictionRunId(), k -> new HashMap<>())
                    .put(item.getCategory(), item);
            }
        }

        Map<String, Long> riskDist = new LinkedHashMap<>();
        riskDist.put("CRITICAL", 0L);
        riskDist.put("HIGH", 0L);
        riskDist.put("MEDIUM", 0L);
        riskDist.put("LOW", 0L);

        List<PredictionDtos.PredictionSummaryRow> summaryRows = new ArrayList<>();
        long highRiskCount = 0;
        for (PredictionRunEntity run : latestRuns) {
            riskDist.merge(run.getOverallRiskLevel(), 1L, Long::sum);
            if ("HIGH".equals(run.getOverallRiskLevel()) || "CRITICAL".equals(run.getOverallRiskLevel())) {
                highRiskCount++;
            }
            Map<String, PredictionItemEntity> cats = itemsByRun.getOrDefault(run.getId(), Map.of());
            PullRequestReviewEntity rev = reviewById.get(run.getReviewId());
            summaryRows.add(new PredictionDtos.PredictionSummaryRow(
                run.getId(),
                run.getReviewId(),
                run.getRepositoryId(),
                repoNames.getOrDefault(run.getRepositoryId(), "repository"),
                run.getPrNumber(),
                rev != null ? rev.getTitle() : "PR #" + run.getPrNumber(),
                run.getOverallEngineeringRiskScore(),
                run.getOverallRiskLevel(),
                levelOf(cats, BugRiskPredictor.CATEGORY),
                levelOf(cats, TestFailurePredictor.CATEGORY),
                levelOf(cats, OperationalRiskPredictor.CATEGORY_INCIDENT),
                levelOf(cats, OperationalRiskPredictor.CATEGORY_CHANGE_FAILURE),
                levelOf(cats, PerformanceRegressionAnalyzer.CATEGORY),
                run.getHistoricalSampleSize(),
                run.getEngineVersion(),
                run.getCreatedAt()
            ));
        }

        List<HistoricalTestExecutionEntity> allTests = testExecutionRepository.findByTenantIdOrderByExecutedAtDesc(tenantId);
        List<DeploymentRecordEntity> allDeployments = deploymentRecordRepository.findByTenantIdOrderByDeployedAtDesc(tenantId);
        List<IncidentRecordEntity> allIncidents = incidentRecordRepository.findByTenantIdOrderByOccurredAtDesc(tenantId);
        List<DefectRecordEntity> allDefects = defectRecordRepository.findByTenantIdOrderByReportedAtDesc(tenantId);

        long totalRollbacks = allDeployments.stream().filter(d -> "ROLLED_BACK".equalsIgnoreCase(d.getOutcome())).count();
        long totalHistoricalRecords = allTests.size() + allDeployments.size() + allIncidents.size() + allDefects.size();
        boolean limitedData = totalHistoricalRecords == 0;
        String historyNotice = limitedData
            ? "No historical test executions, deployments, incidents, or defects have been ingested yet. "
                + "Predictions currently run on deterministic static/structural baselines with explicit limited-data warnings."
            : "Grounded in " + allTests.size() + " test execution(s), " + allDeployments.size() + " deployment(s) ("
                + totalRollbacks + " rollback(s)), " + allIncidents.size() + " confirmed incident(s), and "
                + allDefects.size() + " defect record(s).";

        List<PredictionDtos.ProblematicModuleDto> hotspots = buildProblematicModules(repoNames, allDefects, allIncidents, allTests);
        List<PredictionDtos.TestReliabilityDto> testRel = buildTestReliability(allTests);
        List<PredictionDtos.DeploymentTrendDto> deployTrends = buildDeploymentTrends(allDeployments);
        List<PredictionDtos.IncidentRecordDto> recentIncidents = allIncidents.stream().limit(8).map(this::toIncidentDto).toList();
        List<PredictionDtos.ModelEvaluationDto> evals = evaluationRepository.findByTenantIdOrderByEvaluatedAtDesc(tenantId)
            .stream().limit(8).map(evaluationService::toDto).toList();

        PredictionDtos.PredictiveOverviewResponse response = new PredictionDtos.PredictiveOverviewResponse(
            latestRuns.size(),
            highRiskCount,
            allTests.size(),
            allDeployments.size(),
            totalRollbacks,
            allIncidents.size(),
            allDefects.size(),
            limitedData,
            historyNotice,
            riskDist,
            summaryRows.stream().limit(20).toList(),
            hotspots,
            testRel,
            deployTrends,
            recentIncidents,
            evals
        );

        cacheService.put(cacheKey, writeJsonSafe(response, "{}"), Duration.ofSeconds(30));
        return response;
    }

    private PredictionDtos.PrPredictionResponse buildPrPredictionResponse(String tenantId, PredictionRunEntity run) {
        PullRequestReviewEntity review = requireReview(tenantId, run.getReviewId());
        String repoName = repositoryJpaRepository.findByIdAndTenantId(run.getRepositoryId(), tenantId)
            .map(RepositoryEntity::getFullName).orElse("repository");

        List<PredictionItemEntity> items = itemRepository
            .findByTenantIdAndPredictionRunIdOrderByRiskScoreDesc(tenantId, run.getId());
        List<String> itemIds = items.stream().map(PredictionItemEntity::getId).toList();

        Map<String, List<PredictionDtos.EvidenceDto>> evByItem = new HashMap<>();
        if (!itemIds.isEmpty()) {
            for (PredictionEvidenceEntity ev : evidenceRepository.findByTenantIdAndPredictionItemIdIn(tenantId, itemIds)) {
                evByItem.computeIfAbsent(ev.getPredictionItemId(), k -> new ArrayList<>())
                    .add(new PredictionDtos.EvidenceDto(
                        ev.getId(), ev.getSignalSource(), ev.getEvidenceType(), ev.getTargetRef(),
                        ev.getSummary(), ev.getWeight(), ev.getHistoricalCount(), ev.getObservedAt()
                    ));
            }
        }

        List<PredictionDtos.PredictionItemDto> itemDtos = items.stream()
            .map(it -> new PredictionDtos.PredictionItemDto(
                it.getId(),
                it.getCategory(),
                it.getRiskLevel(),
                it.getRiskScore(),
                it.getProbability(),
                it.getConfidenceLevel(),
                it.isInsufficientData(),
                it.getHistoricalSampleSize(),
                it.getDataFreshnessAt(),
                it.getRuleOrModelVersion(),
                it.getExplanation(),
                it.getLimitations(),
                it.getRecommendedAction(),
                parseTargets(it.getTargetBreakdownJson()),
                evByItem.getOrDefault(it.getId(), List.of())
            ))
            .toList();

        return new PredictionDtos.PrPredictionResponse(
            run.getId(),
            run.getReviewId(),
            run.getRepositoryId(),
            repoName,
            run.getPrNumber(),
            review.getTitle(),
            run.getCommitSha(),
            run.getEngineVersion(),
            run.getModelType(),
            run.getOverallEngineeringRiskScore(),
            run.getOverallRiskLevel(),
            EngineeringRiskAggregator.POLICY_BOUNDARY_NOTE,
            run.getHistoricalSampleSize(),
            run.getDataFreshnessAt(),
            run.getDataAvailabilitySummary(),
            parseSignals(run.getSignalBreakdownJson()),
            itemDtos,
            parseFeatureMap(run.getFeatureSnapshotJson()),
            run.getCreatedAt()
        );
    }

    private List<PredictionDtos.ProblematicModuleDto> buildProblematicModules(
        Map<String, String> repoNames,
        List<DefectRecordEntity> defects,
        List<IncidentRecordEntity> incidents,
        List<HistoricalTestExecutionEntity> tests
    ) {
        record ModKey(String repoId, String module) {}
        Map<ModKey, long[]> counts = new LinkedHashMap<>();
        for (DefectRecordEntity d : defects) {
            String mod = d.getModuleName() != null ? d.getModuleName() : "general";
            counts.computeIfAbsent(new ModKey(d.getRepositoryId(), mod), k -> new long[3])[0]++;
        }
        for (IncidentRecordEntity i : incidents) {
            List<String> mods = featureExtractor.parseStringList(i.getAffectedModulesJson());
            if (mods.isEmpty()) mods = List.of(i.getServiceName());
            for (String m : mods) {
                counts.computeIfAbsent(new ModKey(i.getRepositoryId(), m), k -> new long[3])[1]++;
            }
        }
        for (HistoricalTestExecutionEntity t : tests) {
            if ("FAILED".equalsIgnoreCase(t.getOutcome()) || "FLAKY".equalsIgnoreCase(t.getOutcome())) {
                String mod = t.getModuleName() != null ? t.getModuleName() : "general";
                counts.computeIfAbsent(new ModKey(t.getRepositoryId(), mod), k -> new long[3])[2]++;
            }
        }
        return counts.entrySet().stream()
            .map(e -> new PredictionDtos.ProblematicModuleDto(
                e.getKey().module(),
                repoNames.getOrDefault(e.getKey().repoId(), "repository"),
                e.getValue()[0], e.getValue()[1], e.getValue()[2]
            ))
            .sorted(Comparator.comparingLong((PredictionDtos.ProblematicModuleDto m) ->
                m.defectCount() * 3 + m.incidentCount() * 4 + m.testFailureCount()).reversed())
            .limit(8)
            .toList();
    }

    private List<PredictionDtos.TestReliabilityDto> buildTestReliability(List<HistoricalTestExecutionEntity> tests) {
        Map<String, List<HistoricalTestExecutionEntity>> grouped = new LinkedHashMap<>();
        for (HistoricalTestExecutionEntity t : tests) {
            grouped.computeIfAbsent(t.getTestName(), k -> new ArrayList<>()).add(t);
        }
        return grouped.entrySet().stream()
            .map(e -> {
                List<HistoricalTestExecutionEntity> runs = e.getValue();
                long failed = runs.stream().filter(r -> "FAILED".equalsIgnoreCase(r.getOutcome())).count();
                long flaky = runs.stream().filter(r -> "FLAKY".equalsIgnoreCase(r.getOutcome())).count();
                long passed = runs.stream().filter(r -> "PASSED".equalsIgnoreCase(r.getOutcome())).count();
                double passPct = runs.isEmpty() ? 0.0 : Math.round(((double) passed / runs.size()) * 1000.0) / 10.0;
                String path = runs.stream().map(HistoricalTestExecutionEntity::getTestFilePath)
                    .filter(p -> p != null && !p.isBlank()).findFirst().orElse("");
                return new PredictionDtos.TestReliabilityDto(e.getKey(), path, runs.size(), failed, flaky, passPct);
            })
            .sorted(Comparator.comparingDouble(PredictionDtos.TestReliabilityDto::passRatePct))
            .limit(10)
            .toList();
    }

    private List<PredictionDtos.DeploymentTrendDto> buildDeploymentTrends(List<DeploymentRecordEntity> deployments) {
        Map<String, List<DeploymentRecordEntity>> bySvc = new LinkedHashMap<>();
        for (DeploymentRecordEntity d : deployments) {
            bySvc.computeIfAbsent(d.getServiceName(), k -> new ArrayList<>()).add(d);
        }
        return bySvc.entrySet().stream()
            .map(e -> {
                List<DeploymentRecordEntity> list = e.getValue();
                long failed = list.stream().filter(d -> "FAILED".equalsIgnoreCase(d.getOutcome())).count();
                long rollbacks = list.stream().filter(d -> "ROLLED_BACK".equalsIgnoreCase(d.getOutcome())).count();
                long ok = list.stream().filter(d -> "SUCCEEDED".equalsIgnoreCase(d.getOutcome())).count();
                double rate = list.isEmpty() ? 0.0 : Math.round(((double) ok / list.size()) * 1000.0) / 10.0;
                return new PredictionDtos.DeploymentTrendDto(e.getKey(), list.size(), failed, rollbacks, rate);
            })
            .sorted(Comparator.comparingLong(PredictionDtos.DeploymentTrendDto::totalDeployments).reversed())
            .limit(8)
            .toList();
    }

    private PullRequestReviewEntity requireReview(String tenantId, String reviewId) {
        return reviewRepository.findByIdAndTenantId(reviewId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Pull request review not found: " + reviewId));
    }

    private String levelOf(Map<String, PredictionItemEntity> map, String category) {
        PredictionItemEntity it = map.get(category);
        return it != null ? it.getRiskLevel() : "INSUFFICIENT_DATA";
    }

    private PredictionDtos.ActualTestExecutionItem toActualTestDto(HistoricalTestExecutionEntity t) {
        return new PredictionDtos.ActualTestExecutionItem(
            t.getId(), t.getTestName(), t.getTestFilePath(), t.getOutcome(),
            t.getDurationMs(), t.getErrorSummary(), t.getExecutedAt()
        );
    }

    private PredictionDtos.IncidentRecordDto toIncidentDto(IncidentRecordEntity i) {
        return new PredictionDtos.IncidentRecordDto(
            i.getId(), i.getRepositoryId(), i.getTitle(), i.getSeverity(), i.getServiceName(),
            featureExtractor.parseStringList(i.getAffectedModulesJson()), i.getRelatedDeploymentId(),
            i.getRelatedPrNumber(), i.getStatus(), i.getEvidenceNote(), i.getOccurredAt(), i.getResolvedAt()
        );
    }

    private List<PredictionDtos.TargetRiskBreakdown> dedupTargets(List<PredictionDtos.TargetRiskBreakdown> list) {
        Map<String, PredictionDtos.TargetRiskBreakdown> best = new LinkedHashMap<>();
        for (var tb : list) {
            String key = tb.scope() + ":" + tb.name();
            PredictionDtos.TargetRiskBreakdown existing = best.get(key);
            if (existing == null || tb.riskScore() > existing.riskScore()) {
                best.put(key, tb);
            }
        }
        return best.values().stream()
            .sorted(Comparator.comparingInt(PredictionDtos.TargetRiskBreakdown::riskScore).reversed())
            .toList();
    }

    private List<PredictionDtos.TargetRiskBreakdown> parseTargets(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<PredictionDtos.SignalChannelScore> parseSignals(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, Object> parseFeatureMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String writeJsonSafe(Object obj, String fallback) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            return fallback;
        }
    }
}
