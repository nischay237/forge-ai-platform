package com.forge.platform.prediction;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Signal-Separated, Deduplicated Engineering Risk Aggregator (Milestone 7).
 *
 * Keeps all 7 engineering risk dimensions strictly separate while avoiding double-counting
 * shared underlying evidence (e.g. AST blast radius or destructive DB migrations) in the
 * composite Overall Engineering Risk Score.
 *
 * Policy invariant: Predictive signals NEVER automatically block a PR; merge BLOCK decisions
 * remain controlled solely by deterministic ChangeRiskPolicy rules.
 */
@Component
public class EngineeringRiskAggregator {

    public static final String POLICY_BOUNDARY_NOTE =
        "Predictive risk scores are advisory engineering intelligence signals and NEVER automatically block a PR "
            + "based on uncertain historical estimates. Merge PASS / WARN / BLOCK gates are governed exclusively "
            + "by deterministic workspace Change Risk Policies.";

    public record AggregatedEngineeringRisk(
        int overallScore,
        String overallRiskLevel,
        List<PredictionDtos.SignalChannelScore> signalChannels
    ) {}

    public AggregatedEngineeringRisk aggregate(
        PrFeatureExtractor.PrFeatureVector fv,
        PredictionCandidate.CategoryPredictionDraft bugDraft,
        PredictionCandidate.CategoryPredictionDraft testDraft,
        PredictionCandidate.CategoryPredictionDraft incidentDraft,
        PredictionCandidate.CategoryPredictionDraft changeFailureDraft,
        PredictionCandidate.CategoryPredictionDraft perfDraft
    ) {
        Set<String> countedKeys = new HashSet<>();
        List<PredictionDtos.SignalChannelScore> channels = new ArrayList<>();

        // 1. Static Code Analysis Risk (excluding security & perf)
        int staticRaw = computeStaticScore(fv);
        if (fv.breakingApiCount() > 0) countedKeys.add("api-compat:" + fv.reviewId());
        if (fv.migrationRiskCount() > 0) countedKeys.add("db-migration:" + fv.reviewId());
        int staticContrib = (int) Math.round(staticRaw * 0.24);
        channels.add(new PredictionDtos.SignalChannelScore(
            "STATIC_CODE_ANALYSIS",
            "Static Code & Contract Analysis",
            staticRaw,
            staticContrib,
            BugRiskPredictor.toRiskLevel(staticRaw),
            "DETERMINISTIC_STATIC",
            fv.reviewFindings().size() + " static/contract/migration review finding(s)"
        ));

        // 2. Dependency Impact Risk
        int depRaw = Math.min(100, fv.totalBlastRadius() * 14);
        if (fv.totalBlastRadius() > 0) countedKeys.add("blast-radius:" + fv.reviewId());
        int depContrib = (int) Math.round(depRaw * 0.14);
        channels.add(new PredictionDtos.SignalChannelScore(
            "DEPENDENCY_IMPACT",
            "AST Dependency Blast Radius",
            depRaw,
            depContrib,
            BugRiskPredictor.toRiskLevel(depRaw),
            "DETERMINISTIC_STATIC",
            fv.directImpactedCount() + " direct, " + fv.transitiveImpactedCount() + " transitive dependents"
        ));

        // 3. Security Risk
        int secRaw = computeSecurityScore(fv);
        int secContrib = (int) Math.round(secRaw * 0.22);
        channels.add(new PredictionDtos.SignalChannelScore(
            "SECURITY",
            "Security & Secret Scanning",
            secRaw,
            secContrib,
            BugRiskPredictor.toRiskLevel(secRaw),
            "DETERMINISTIC_STATIC",
            fv.securityFindings().size() + " security finding(s) (" + fv.criticalSecurityCount() + " critical)"
        ));

        // 4. Predicted Defect Risk (deduplicated against blast radius already counted)
        int bugDedup = deduplicatedScore(bugDraft, countedKeys);
        int bugContrib = (int) Math.round(bugDedup * 0.13);
        channels.add(new PredictionDtos.SignalChannelScore(
            "PREDICTED_DEFECT",
            "Predicted Bug / Defect Risk",
            bugDraft.rawScore(),
            bugContrib,
            bugDraft.riskLevel(),
            "PREDICTIVE_BASELINE",
            bugDraft.insufficientData()
                ? "Insufficient historical defect data — structural baseline only (raw " + bugDraft.rawScore() + ", dedup " + bugDedup + ")"
                : "Confidence: " + bugDraft.confidenceLevel() + " (raw " + bugDraft.rawScore() + ", dedup " + bugDedup + ")"
        ));

        // 5. Predicted Test Failure Risk (deduplicated)
        int testDedup = deduplicatedScore(testDraft, countedKeys);
        int testContrib = (int) Math.round(testDedup * 0.10);
        channels.add(new PredictionDtos.SignalChannelScore(
            "PREDICTED_TEST_FAILURE",
            "Predicted Test Failure Risk",
            testDraft.rawScore(),
            testContrib,
            testDraft.riskLevel(),
            "PREDICTIVE_BASELINE",
            testDraft.insufficientData()
                ? "Insufficient historical CI test data — static selection baseline only (not executed)"
                : "Confidence: " + testDraft.confidenceLevel() + " (predicted distinct from actual CI results)"
        ));

        // 6. Predicted Operational Risk (combines Incident + Change Failure with evidence deduplication)
        int incDedup = deduplicatedScore(incidentDraft, countedKeys);
        int cfDedup = deduplicatedScore(changeFailureDraft, countedKeys);
        int opsRaw = Math.max(incidentDraft.rawScore(), changeFailureDraft.rawScore());
        int opsDedup = Math.min(100, Math.max(incDedup, cfDedup) + Math.min(20, Math.min(incDedup, cfDedup) / 2));
        int opsContrib = (int) Math.round(opsDedup * 0.11);
        boolean opsInsufficient = incidentDraft.insufficientData() && changeFailureDraft.insufficientData();
        channels.add(new PredictionDtos.SignalChannelScore(
            "PREDICTED_OPERATIONAL",
            "Predicted Operational (Incident & Deploy/Rollback)",
            opsRaw,
            opsContrib,
            opsInsufficient ? "INSUFFICIENT_DATA" : BugRiskPredictor.toRiskLevel(opsRaw),
            "PREDICTIVE_BASELINE",
            opsInsufficient
                ? "Insufficient historical incident/deployment data — operational risk cannot be assumed zero"
                : "Incident=" + incidentDraft.riskLevel() + " (" + incidentDraft.rawScore()
                    + "), Deploy/Rollback=" + changeFailureDraft.riskLevel() + " (" + changeFailureDraft.rawScore() + ")"
        ));

        // 7. Performance Regression Warnings (explicitly static warnings)
        int perfDedup = deduplicatedScore(perfDraft, countedKeys);
        int perfContrib = (int) Math.round(perfDedup * 0.08);
        channels.add(new PredictionDtos.SignalChannelScore(
            "PERFORMANCE_REGRESSION_WARNING",
            "Static Performance Regression Warnings",
            perfDraft.rawScore(),
            perfContrib,
            perfDraft.riskLevel(),
            "STATIC_PERF_WARNING",
            perfDraft.evidence().isEmpty()
                ? "No static perf warnings detected (no runtime APM telemetry connected)"
                : perfDraft.evidence().size() + " static perf warning(s) — no runtime telemetry connected"
        ));

        int totalContribution = channels.stream()
            .mapToInt(PredictionDtos.SignalChannelScore::deduplicatedContribution)
            .sum();
        int overall = Math.min(100, Math.max(0, totalContribution));
        String overallLevel = BugRiskPredictor.toRiskLevel(overall);

        return new AggregatedEngineeringRisk(overall, overallLevel, channels);
    }

    private int deduplicatedScore(PredictionCandidate.CategoryPredictionDraft draft, Set<String> countedKeys) {
        if (draft.evidence().isEmpty()) {
            return draft.rawScore();
        }
        int dedupSum = 0;
        for (PredictionCandidate.EvidenceDraft ev : draft.evidence()) {
            String key = ev.deduplicationKey();
            if (key == null || key.isBlank() || countedKeys.add(key)) {
                dedupSum += Math.max(0, ev.weight());
            }
        }
        return Math.min(100, dedupSum);
    }

    private int computeStaticScore(PrFeatureExtractor.PrFeatureVector fv) {
        int sum = 0;
        for (var f : fv.reviewFindings()) {
            if ("SECURITY".equalsIgnoreCase(f.getCategory())) continue;
            sum += switch (f.getSeverity()) {
                case "CRITICAL" -> 35;
                case "HIGH" -> 20;
                case "MEDIUM" -> 10;
                default -> 4;
            };
        }
        return Math.min(100, sum);
    }

    private int computeSecurityScore(PrFeatureExtractor.PrFeatureVector fv) {
        int sum = 0;
        for (var s : fv.securityFindings()) {
            sum += switch (s.getSeverity()) {
                case "CRITICAL" -> 40;
                case "HIGH" -> 22;
                case "MEDIUM" -> 10;
                default -> 4;
            };
        }
        for (var f : fv.reviewFindings()) {
            if ("SECURITY".equalsIgnoreCase(f.getCategory()) && fv.securityFindings().isEmpty()) {
                sum += switch (f.getSeverity()) {
                    case "CRITICAL" -> 40;
                    case "HIGH" -> 22;
                    case "MEDIUM" -> 10;
                    default -> 4;
                };
            }
        }
        return Math.min(100, sum);
    }
}
