package com.forge.platform.prediction;

import com.forge.platform.codeintel.CodeIntelligenceService;
import com.forge.platform.review.TestSelectionService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic Baseline Test Failure Predictor (Milestone 4B).
 *
 * Ranks tests most likely to fail after a PR change using changed files/methods, AST reverse
 * dependencies, historical per-test execution failures/flakiness, and failed CI build history.
 *
 * Truth boundary: Strictly distinguishes predicted tests at risk (PREDICTED_AT_RISK) from
 * actual executed CI test results (actualPrTestExecutions).
 */
@Component
public class TestFailurePredictor {

    public static final String CATEGORY = "TEST_FAILURE_RISK";
    public static final String RULE_VERSION = "forge-test-failure-baseline-v1.0";

    public record TestFailurePredictionResult(
        PredictionCandidate.CategoryPredictionDraft categoryDraft,
        List<PredictionDtos.PredictedTestRiskItem> predictedTests
    ) {}

    public TestFailurePredictionResult predict(PrFeatureExtractor.PrFeatureVector fv) {
        List<PredictionCandidate.EvidenceDraft> evidence = new ArrayList<>();
        List<PredictionDtos.TargetRiskBreakdown> targets = new ArrayList<>();
        Map<String, PredictionDtos.PredictedTestRiskItem> predictedMap = new LinkedHashMap<>();

        int historicalTestSamples = fv.repoTestExecutions().size() + fv.repoCiBuilds().size();
        boolean insufficientHistory = historicalTestSamples == 0;

        List<TestSelectionService.SelectedTest> candidates = fv.testSelectionPlan() != null
            ? fv.testSelectionPlan().selectedTests()
            : List.of();

        int maxCandidateScore = 0;
        int failingHistoryMatches = 0;

        // 1. Score statically selected tests enriched with real historical execution records
        for (TestSelectionService.SelectedTest candidate : candidates) {
            PrFeatureExtractor.TestHistoryStats stats = findMatchingStats(candidate.testName(), candidate.testFilePath(), fv.testHistoryByName());

            int testScore = "HIGH".equalsIgnoreCase(candidate.priority()) ? 32 : 18;
            List<String> historyNotes = new ArrayList<>();
            int runs = 0;
            int failures = 0;

            if (stats != null && stats.totalRuns() > 0) {
                runs = stats.totalRuns();
                failures = stats.failedRuns() + stats.flakyRuns();
                if (failures > 0) {
                    failingHistoryMatches++;
                    double failRate = (double) failures / runs;
                    int histBonus = Math.min(48, (int) Math.round(failRate * 55) + Math.min(18, failures * 6));
                    testScore += histBonus;
                    historyNotes.add(failures + " failure/flaky run(s) out of " + runs + " historical executions");
                } else {
                    historyNotes.add("0 failures across " + runs + " historical runs");
                }
            } else {
                historyNotes.add("No prior per-test execution history recorded");
            }

            if (fv.totalBlastRadius() >= 3) {
                testScore += 10;
            }
            if (fv.breakingApiCount() > 0 || fv.migrationRiskCount() > 0) {
                testScore += 15;
            }

            testScore = Math.min(100, Math.max(5, testScore));
            maxCandidateScore = Math.max(maxCandidateScore, testScore);
            String testRiskLevel = BugRiskPredictor.toRiskLevel(testScore);
            String testConfidence = runs >= 5 ? "HIGH" : (runs >= 1 ? "MEDIUM" : "LOW");

            predictedMap.putIfAbsent(candidate.testName(), new PredictionDtos.PredictedTestRiskItem(
                candidate.testName(),
                candidate.testFilePath(),
                testRiskLevel,
                testScore,
                testConfidence,
                candidate.selectionReason(),
                String.join("; ", historyNotes),
                runs,
                failures,
                candidate.suggestedCommand(),
                "PREDICTED_AT_RISK"
            ));

            targets.add(new PredictionDtos.TargetRiskBreakdown(
                "FILE",
                candidate.testName(),
                candidate.testFilePath(),
                testRiskLevel,
                testScore,
                candidate.selectionReason() + " (" + String.join("; ", historyNotes) + ")"
            ));
        }

        // 2. Also include historically failing/flaky tests in the same affected modules even if not statically named
        for (PrFeatureExtractor.TestHistoryStats stats : fv.testHistoryByName().values()) {
            int failures = stats.failedRuns() + stats.flakyRuns();
            if (failures == 0 || predictedMap.containsKey(stats.testName())) continue;

            boolean moduleOverlap = fv.affectedModules().stream().anyMatch(m ->
                (stats.testFilePath() != null && stats.testFilePath().toLowerCase().contains(m.toLowerCase()))
                    || stats.testName().toLowerCase().contains(m.toLowerCase()));
            if (!moduleOverlap) continue;

            failingHistoryMatches++;
            double failRate = stats.totalRuns() > 0 ? (double) failures / stats.totalRuns() : 0.0;
            int testScore = Math.min(100, 28 + (int) Math.round(failRate * 50) + Math.min(15, failures * 5));
            maxCandidateScore = Math.max(maxCandidateScore, testScore);
            String testRiskLevel = BugRiskPredictor.toRiskLevel(testScore);

            predictedMap.put(stats.testName(), new PredictionDtos.PredictedTestRiskItem(
                stats.testName(),
                stats.testFilePath() != null ? stats.testFilePath() : "module:" + String.join(",", fv.affectedModules()),
                testRiskLevel,
                testScore,
                stats.totalRuns() >= 5 ? "HIGH" : "MEDIUM",
                "Historically failing test overlapping modified module(s) " + fv.affectedModules(),
                failures + " failure/flaky run(s) out of " + stats.totalRuns() + " recorded executions",
                stats.totalRuns(),
                failures,
                "./mvnw test -Dtest=" + stats.testName().replaceAll("\\s.*", ""),
                "PREDICTED_AT_RISK"
            ));
        }

        // 3. Build aggregate category score & supporting evidence
        int score = 0;
        if (failingHistoryMatches > 0) {
            int histWeight = Math.min(40, failingHistoryMatches * 16);
            score += histWeight;
            Instant latestTestRun = fv.repoTestExecutions().stream()
                .map(HistoricalTestExecutionEntity::getExecutedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "HISTORICAL_TEST_EXECUTIONS",
                "FREQUENTLY_FAILING_TESTS",
                failingHistoryMatches + " test(s)",
                failingHistoryMatches + " related test(s) have prior failure/flaky records in test execution history",
                histWeight,
                failingHistoryMatches,
                latestTestRun,
                "test-history:" + fv.reviewId()
            ));
        }

        if (!fv.failedCiBuildsTouchingPr().isEmpty()) {
            int ciWeight = Math.min(24, fv.failedCiBuildsTouchingPr().size() * 12);
            score += ciWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "CI_PIPELINE_HISTORY",
                "RECENT_CI_FAILURES",
                fv.failedCiBuildsTouchingPr().get(0).getPipelineName(),
                fv.failedCiBuildsTouchingPr().size() + " recent failed CI build(s) referenced files/modules touched by this PR",
                ciWeight,
                fv.failedCiBuildsTouchingPr().size(),
                fv.failedCiBuildsTouchingPr().get(0).getTriggeredAt(),
                "ci-fail-history:" + fv.reviewId()
            ));
        }

        if (fv.totalBlastRadius() >= 2) {
            int depWeight = Math.min(20, fv.totalBlastRadius() * 4);
            score += depWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "DEPENDENCY_GRAPH",
                "SHARED_DEPENDENCY_TEST_SURFACE",
                fv.totalBlastRadius() + " dependents",
                "Changed code impacts " + fv.totalBlastRadius() + " dependent nodes with "
                    + predictedMap.size() + " tests predicted at risk",
                depWeight,
                0,
                null,
                "blast-radius:" + fv.reviewId() // shares key with BugRisk for deduplicated aggregation
            ));
        }

        if (fv.breakingApiCount() > 0 || fv.migrationRiskCount() > 0) {
            int compatWeight = 20;
            score += compatWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "COMPATIBILITY_CHANGES",
                "CONTRACT_OR_SCHEMA_CHANGE",
                "api=" + fv.breakingApiCount() + ",migration=" + fv.migrationRiskCount(),
                "Breaking API or database migration changes frequently trigger integration and contract test failures",
                compatWeight,
                0,
                null,
                "compat-change:" + fv.reviewId()
            ));
        }

        if (score == 0 && maxCandidateScore > 0) {
            score = Math.min(35, maxCandidateScore);
        }
        int finalScore = Math.min(100, Math.max(0, score));
        String riskLevel = insufficientHistory && finalScore < 20 ? "INSUFFICIENT_DATA" : BugRiskPredictor.toRiskLevel(finalScore);
        String confidence = assessConfidence(historicalTestSamples, failingHistoryMatches > 0, !predictedMap.isEmpty());

        String explanation;
        if (insufficientHistory && evidence.isEmpty()) {
            explanation = PredictionDtos.INSUFFICIENT_DATA_MESSAGE
                + " No prior CI build or per-test execution history is recorded; "
                + predictedMap.size() + " candidate test(s) were selected structurally.";
        } else {
            String top = evidence.stream().limit(3).map(PredictionCandidate.EvidenceDraft::summary)
                .reduce((a, b) -> a + "; " + b).orElse("Structural test dependency mapping");
            explanation = (insufficientHistory ? "Structural baseline (limited test history): " : riskLevel + " test failure risk (" + finalScore + "/100): ")
                + top + ". Predictions are distinct from actual CI execution results.";
        }

        String limitations = insufficientHistory
            ? PredictionDtos.INSUFFICIENT_DATA_MESSAGE
                + " Ingest CI test executions (or connect GitHub workflow_run webhooks) to enable historical failure correlation."
            : "Based on " + fv.repoTestExecutions().size() + " recorded test execution(s) and " + fv.repoCiBuilds().size()
                + " CI build(s). Predicted tests at risk are NOT executed by FORGE; confirm actual results once CI completes.";

        String action = predictedMap.isEmpty()
            ? "Add unit/integration test coverage for changed files and execute the CI test suite before merge."
            : "Run the " + predictedMap.size() + " predicted at-risk test(s) locally or in CI prior to merge and inspect actual test outcomes.";

        List<PredictionDtos.PredictedTestRiskItem> sortedTests = predictedMap.values().stream()
            .sorted(Comparator.comparingInt(PredictionDtos.PredictedTestRiskItem::riskScore).reversed())
            .toList();

        PredictionCandidate.CategoryPredictionDraft draft = new PredictionCandidate.CategoryPredictionDraft(
            CATEGORY,
            finalScore,
            riskLevel,
            null,
            confidence,
            insufficientHistory,
            historicalTestSamples,
            fv.dataFreshnessAt(),
            RULE_VERSION,
            explanation,
            limitations,
            action,
            targets,
            evidence
        );
        return new TestFailurePredictionResult(draft, sortedTests);
    }

    private PrFeatureExtractor.TestHistoryStats findMatchingStats(
        String testName, String testFilePath, Map<String, PrFeatureExtractor.TestHistoryStats> statsMap
    ) {
        if (statsMap.containsKey(testName)) return statsMap.get(testName);
        String cleanName = testName.replaceAll("\\s.*", "");
        if (statsMap.containsKey(cleanName)) return statsMap.get(cleanName);
        String simpleFromPath = testFilePath != null ? CodeIntelligenceService.extractSimpleName(testFilePath) : "";
        for (var entry : statsMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(cleanName)
                || (!simpleFromPath.isBlank() && entry.getKey().equalsIgnoreCase(simpleFromPath))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String assessConfidence(int historicalTestSamples, boolean hasFailureHistory, boolean hasCandidates) {
        if (historicalTestSamples >= 10 && hasFailureHistory) return "HIGH";
        if (historicalTestSamples >= 3 || hasFailureHistory) return "MEDIUM";
        if (hasCandidates) return "LOW";
        return "INSUFFICIENT_DATA";
    }
}
