package com.forge.platform.prediction;

import com.forge.platform.codeintel.CodeIntelDtos;
import com.forge.platform.review.TestSelectionService;
import com.forge.platform.review.UnifiedDiffParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PredictiveEnginesUnitTest {

    private final UnifiedDiffParser diffParser = new UnifiedDiffParser();
    private final BugRiskPredictor bugRiskPredictor = new BugRiskPredictor();
    private final TestFailurePredictor testFailurePredictor = new TestFailurePredictor();
    private final OperationalRiskPredictor operationalRiskPredictor = new OperationalRiskPredictor();
    private final PerformanceRegressionAnalyzer performanceAnalyzer = new PerformanceRegressionAnalyzer();
    private final EngineeringRiskAggregator aggregator = new EngineeringRiskAggregator();

    @Test
    void detectsStaticPerformanceAntiPatternsAndNeverClaimsMlOrMeasuredLatency() {
        String perfDiff = """
            diff --git a/src/main/java/com/acme/payments/SettlementController.java b/src/main/java/com/acme/payments/SettlementController.java
            --- a/src/main/java/com/acme/payments/SettlementController.java
            +++ b/src/main/java/com/acme/payments/SettlementController.java
            @@ -10,4 +10,20 @@
            -    @Cacheable("settlements")
                 public List<Settlement> loadAll(List<String> ids) throws Exception {
            +        byte[] blob = Files.readAllBytes(Path.of("/data/dump.bin"));
            +        List<Settlement> all = settlementRepository.findAll();
            +        for (String id : ids) {
            +            settlementRepository.findById(id);
            +            restTemplate.getForObject("https://partner.example/check/" + id, String.class);
            +            for (Settlement s : all) {
            +                if (all.contains(s)) {}
            +            }
            +        }
            +        cacheManager.getCache("settlements").clear();
                     return all;
                 }
            """;

        PrFeatureExtractor.PrFeatureVector fv = buildVector(perfDiff, true, false, false, false, 0, 0, 0, List.of(), List.of(), Map.of(), List.of(), List.of());
        PredictionCandidate.CategoryPredictionDraft perf = performanceAnalyzer.analyze(fv);

        assertThat(perf.category()).isEqualTo(PerformanceRegressionAnalyzer.CATEGORY);
        assertThat(perf.rawScore()).isGreaterThanOrEqualTo(60);
        assertThat(perf.probability()).isNull(); // Never invent performance regression probabilities
        assertThat(perf.limitations()).containsIgnoringCase("static code warnings");
        assertThat(perf.limitations()).containsIgnoringCase("NOT ML");
        assertThat(perf.evidence()).extracting(PredictionCandidate.EvidenceDraft::evidenceType)
            .contains(
                "PERF_CACHE_REMOVED",
                "PERF_MEMORY_ALLOCATION",
                "PERF_EXPENSIVE_DB_QUERY",
                "PERF_N_PLUS_ONE_QUERY",
                "PERF_SYNC_IO_IN_LOOP",
                "PERF_NESTED_LOOP_COMPLEXITY",
                "PERF_BROAD_CACHE_INVALIDATION"
            );
    }

    @Test
    void reportsInsufficientHistoricalDataAndOmitsUncalibratedProbabilitiesWhenHistoryMissing() {
        String simpleDiff = """
            diff --git a/src/main/java/com/acme/util/FormatHelper.java b/src/main/java/com/acme/util/FormatHelper.java
            --- a/src/main/java/com/acme/util/FormatHelper.java
            +++ b/src/main/java/com/acme/util/FormatHelper.java
            @@ -1,2 +1,4 @@
             public class FormatHelper {
            +    public String clean(String v) { return v == null ? "" : v.trim(); }
             }
            """;

        PrFeatureExtractor.PrFeatureVector fv = buildVector(
            simpleDiff, false, false, false, false, 0, 0, 0,
            List.of(), List.of(), Map.of(), List.of(), List.of()
        );

        var bug = bugRiskPredictor.predict(fv);
        var testRes = testFailurePredictor.predict(fv);
        var inc = operationalRiskPredictor.predictIncidentRisk(fv);
        var cf = operationalRiskPredictor.predictChangeFailureRisk(fv);

        assertThat(bug.insufficientData()).isTrue();
        assertThat(bug.probability()).isNull();
        assertThat(bug.limitations()).contains(PredictionDtos.INSUFFICIENT_DATA_MESSAGE);

        assertThat(testRes.categoryDraft().insufficientData()).isTrue();
        assertThat(testRes.categoryDraft().probability()).isNull();

        assertThat(inc.insufficientData()).isTrue();
        assertThat(inc.riskLevel()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(inc.probability()).isNull();

        assertThat(cf.insufficientData()).isTrue();
        assertThat(cf.riskLevel()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(cf.probability()).isNull();
    }

    @Test
    void separatesSignalsDeduplicatesSharedEvidenceAndKeepsDeploymentOutcomesDistinctFromIncidents() {
        String riskyDiff = """
            diff --git a/src/main/java/com/acme/payments/PaymentAuthService.java b/src/main/java/com/acme/payments/PaymentAuthService.java
            --- a/src/main/java/com/acme/payments/PaymentAuthService.java
            +++ b/src/main/java/com/acme/payments/PaymentAuthService.java
            @@ -1,2 +1,8 @@
             public class PaymentAuthService {
            +    public void authorize(String token) {
            +        if (token != null && !token.isEmpty()) {
            +            restTemplate.postForObject("https://psp.example/auth", token, String.class);
            +        }
            +    }
             }
            """;

        DefectRecordEntity defect = DefectRecordEntity.create(
            "t1", "r1", "Double charge bug in PaymentAuthService", "HIGH", "payments",
            "[\"src/main/java/com/acme/payments/PaymentAuthService.java\"]", 11, null, "OPEN", "def-1", Instant.now()
        );
        IncidentRecordEntity incident = IncidentRecordEntity.create(
            "t1", "r1", "Payments auth outage", "SEV1", "payments-api",
            "[\"payments\"]", null, 11, "RESOLVED", "Observed error spike", "inc-1", Instant.now(), Instant.now()
        );
        DeploymentRecordEntity failedDep = DeploymentRecordEntity.create(
            "t1", "r1", "payments-api", "production", 10, "sha10", "FAILED", null, true, "CrashLoop", "dep-1", Instant.now()
        );
        DeploymentRecordEntity rollbackDep = DeploymentRecordEntity.create(
            "t1", "r1", "payments-api", "production", 11, "sha11", "ROLLED_BACK", failedDep.getId(), false, "Reverted", "dep-2", Instant.now()
        );
        var testStats = Map.of(
            "PaymentAuthServiceTest",
            new PrFeatureExtractor.TestHistoryStats("PaymentAuthServiceTest", "src/test/java/PaymentAuthServiceTest.java", 6, 4, 1, Instant.now())
        );

        PrFeatureExtractor.PrFeatureVector fv = buildVector(
            riskyDiff, true, true, true, true, 1, 1, 4,
            List.of(defect), List.of(incident), testStats, List.of(failedDep, rollbackDep),
            List.of(new TestSelectionService.SelectedTest(
                "PaymentAuthServiceTest", "src/test/java/PaymentAuthServiceTest.java",
                "Direct unit test", "src/main/java/com/acme/payments/PaymentAuthService.java",
                "HIGH", "./mvnw test -Dtest=PaymentAuthServiceTest", TestSelectionService.EXECUTION_STATUS_NOT_EXECUTED
            ))
        );

        var bug = bugRiskPredictor.predict(fv);
        var testRes = testFailurePredictor.predict(fv);
        var inc = operationalRiskPredictor.predictIncidentRisk(fv);
        var cf = operationalRiskPredictor.predictChangeFailureRisk(fv);
        var perf = performanceAnalyzer.analyze(fv);

        assertThat(bug.explanation()).doesNotContain("contains a bug");
        assertThat(bug.explanation()).containsIgnoringCase("susceptibility");
        assertThat(inc.explanation()).containsIgnoringCase("never claims causal");
        assertThat(cf.limitations()).contains("1 failed, 1 rolled back");
        assertThat(testRes.predictedTests()).hasSize(1);
        assertThat(testRes.predictedTests().get(0).predictionState()).isEqualTo("PREDICTED_AT_RISK");
        assertThat(testRes.predictedTests().get(0).historicalFailures()).isEqualTo(5);

        EngineeringRiskAggregator.AggregatedEngineeringRisk agg = aggregator.aggregate(fv, bug, testRes.categoryDraft(), inc, cf, perf);
        assertThat(agg.signalChannels()).hasSize(7);
        assertThat(agg.signalChannels()).extracting(PredictionDtos.SignalChannelScore::channel).containsExactly(
            "STATIC_CODE_ANALYSIS",
            "DEPENDENCY_IMPACT",
            "SECURITY",
            "PREDICTED_DEFECT",
            "PREDICTED_TEST_FAILURE",
            "PREDICTED_OPERATIONAL",
            "PERFORMANCE_REGRESSION_WARNING"
        );
        // Deduplication ensures blast-radius is NOT double-counted inside PREDICTED_DEFECT
        var defectChannel = agg.signalChannels().stream()
            .filter(c -> c.channel().equals("PREDICTED_DEFECT")).findFirst().orElseThrow();
        assertThat(defectChannel.summary()).contains("dedup");
        assertThat(EngineeringRiskAggregator.POLICY_BOUNDARY_NOTE).containsIgnoringCase("NEVER automatically block");
    }

    @Test
    void performanceAnalyzerTracksLoopBraceDepthAccuratelyAndDeduplicatesRepeatedStaticWarnings() {
        String nonNestedDiff = """
            diff --git a/src/main/java/com/acme/reporting/ReportQueryService.java b/src/main/java/com/acme/reporting/ReportQueryService.java
            --- a/src/main/java/com/acme/reporting/ReportQueryService.java
            +++ b/src/main/java/com/acme/reporting/ReportQueryService.java
            @@ -1,2 +1,16 @@
             public class ReportQueryService {
            +    public void run(List<String> rows, List<String> summaries) {
            +        for (String r : rows) {
            +            if (r != null) {
            +                log.debug(r);
            +            }
            +        }
            +        for (String s : summaries) {
            +            log.debug(s);
            +        }
            +        String q1 = "SELECT * FROM audit_logs";
            +        String q2 = "SELECT * FROM review_findings";
            +        String q3 = "SELECT * FROM repositories";
            +    }
             }
            """;

        PrFeatureExtractor.PrFeatureVector fv = buildVector(
            nonNestedDiff, false, false, false, false, 0, 0, 0,
            List.of(), List.of(), Map.of(), List.of(), List.of()
        );
        PredictionCandidate.CategoryPredictionDraft perf = performanceAnalyzer.analyze(fv);

        assertThat(perf.evidence())
            .extracting(PredictionCandidate.EvidenceDraft::evidenceType)
            .doesNotContain("PERF_NESTED_LOOP_COMPLEXITY");
        long expensiveQueryEvidenceCount = perf.evidence().stream()
            .filter(e -> "PERF_EXPENSIVE_DB_QUERY".equals(e.evidenceType()))
            .count();
        assertThat(expensiveQueryEvidenceCount).isEqualTo(1L);
    }

    @Test
    void operationalRiskUsesActualFailedAndRollbackTimestampsRatherThanLatestSuccessfulDeploy() {
        String diff = """
            diff --git a/src/main/java/com/acme/payments/CheckoutConfig.java b/src/main/java/com/acme/payments/CheckoutConfig.java
            --- a/src/main/java/com/acme/payments/CheckoutConfig.java
            +++ b/src/main/java/com/acme/payments/CheckoutConfig.java
            @@ -1,2 +1,4 @@
             public class CheckoutConfig {
            +    private final int timeoutMs = 5000;
             }
            """;

        Instant latestSuccessAt = Instant.parse("2026-04-02T12:00:00Z");
        Instant failedAt = Instant.parse("2026-03-10T08:30:00Z");
        Instant rollbackAt = Instant.parse("2026-03-15T14:45:00Z");

        DeploymentRecordEntity latestSuccess = DeploymentRecordEntity.create(
            "t1", "r1", "payments-api", "production", 20, "sha20", "SUCCEEDED", null, false, "Healthy", "dep-ok", latestSuccessAt
        );
        DeploymentRecordEntity rolledBack = DeploymentRecordEntity.create(
            "t1", "r1", "payments-api", "production", 15, "sha15", "ROLLED_BACK", null, false, "Reverted", "dep-rb", rollbackAt
        );
        DeploymentRecordEntity failed = DeploymentRecordEntity.create(
            "t1", "r1", "payments-api", "production", 10, "sha10", "FAILED", null, true, "CrashLoop", "dep-fail", failedAt
        );

        PrFeatureExtractor.PrFeatureVector fv = buildVector(
            diff, false, false, true, false, 0, 0, 0,
            List.of(), List.of(), Map.of(), List.of(latestSuccess, rolledBack, failed), List.of()
        );
        PredictionCandidate.CategoryPredictionDraft cf = operationalRiskPredictor.predictChangeFailureRisk(fv);

        var failedEvidence = cf.evidence().stream()
            .filter(e -> "PRIOR_DEPLOYMENT_FAILURES".equals(e.evidenceType()))
            .findFirst().orElseThrow();
        var rollbackEvidence = cf.evidence().stream()
            .filter(e -> "PRIOR_DEPLOYMENT_ROLLBACKS".equals(e.evidenceType()))
            .findFirst().orElseThrow();

        assertThat(failedEvidence.observedAt()).isEqualTo(failedAt);
        assertThat(rollbackEvidence.observedAt()).isEqualTo(rollbackAt);
    }

    private PrFeatureExtractor.PrFeatureVector buildVector(
        String rawDiff,
        boolean criticalPath,
        boolean auth,
        boolean config,
        boolean external,
        int breakingApi,
        int migrationRisk,
        int blastRadius,
        List<DefectRecordEntity> defects,
        List<IncidentRecordEntity> incidents,
        Map<String, PrFeatureExtractor.TestHistoryStats> testStats,
        List<DeploymentRecordEntity> deployments,
        List<TestSelectionService.SelectedTest> selectedTests
    ) {
        List<UnifiedDiffParser.ParsedDiffFile> parsed = diffParser.parse(rawDiff);
        List<String> paths = parsed.stream().map(UnifiedDiffParser.ParsedDiffFile::filePath).toList();
        long failedDeploys = deployments.stream().filter(d -> "FAILED".equals(d.getOutcome())).count();
        long rollbacks = deployments.stream().filter(d -> "ROLLED_BACK".equals(d.getOutcome())).count();
        int totalSamples = defects.size() + incidents.size() + deployments.size() + testStats.size();

        List<CodeIntelDtos.ChangeImpactResponse> impacts = blastRadius > 0
            ? List.of(new CodeIntelDtos.ChangeImpactResponse(
                "PaymentAuthService", "src/main/java/com/acme/payments/PaymentAuthService.java",
                blastRadius, blastRadius, "HIGH", List.of(), List.of()))
            : List.of();

        return new PrFeatureExtractor.PrFeatureVector(
            "t1", "r1", "acme/payments-api", "payments-api", "rev-1", 42, "test PR", "sha42",
            parsed, paths, Set.of("payments"), 20, 2, 22,
            false, auth, config, external, migrationRisk > 0, criticalPath,
            Map.of(), 10, 8.0, impacts, blastRadius, 0, blastRadius, Set.of("payments"),
            List.of(), List.of(), null,
            new TestSelectionService.TestSelectionPlan("NOT_EXECUTED", "disclaimer", false, selectedTests.size(), selectedTests, List.of()),
            breakingApi, migrationRisk, 0,
            0, Map.of(), defects, defects.size(),
            List.of(), List.of(), testStats,
            List.of(), List.of(),
            deployments, failedDeploys, rollbacks,
            incidents, incidents.size(), totalSamples,
            totalSamples > 0 ? Instant.now() : null,
            "summary"
        );
    }
}
