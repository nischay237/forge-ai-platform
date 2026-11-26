package com.forge.platform.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.codeintel.CodeIntelDtos;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic, Explainable Change Risk Intelligence Engine (Part 4F).
 *
 * Computes a transparent 0-100 risk score and PASS / WARN / BLOCK merge recommendation
 * exclusively from deterministic signals (security, API compatibility, database migration safety,
 * AST dependency blast radius, sensitive path touchpoints, correctness defects, and test coverage)
 * governed by tenant Policy-as-Code.
 *
 * AI never independently invents or overrides the merge decision.
 */
@Component
public class ChangeRiskEngine {

    public static final String PASS_SAFETY_DISCLAIMER =
        "PASS indicates no configured blocking policy condition was triggered during static, contract, "
            + "and dependency analysis. It is NOT a guarantee of production safety.";

    public record RiskFactor(
        String category,
        String title,
        String severity,
        int riskPoints,
        double confidence,
        String evidence,
        String explanation,
        String policyRule
    ) {}

    public record ChangeRiskAssessment(
        int riskScore,
        String mergeDecision,
        int policyVersion,
        String decisionSummary,
        String safetyDisclaimer,
        List<String> triggeredPolicies,
        List<RiskFactor> factors,
        int impactedModulesCount,
        int breakingApiCount,
        int migrationRiskCount,
        int criticalSecurityCount
    ) {}

    private final ObjectMapper objectMapper;

    public ChangeRiskEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ChangeRiskAssessment evaluate(
        List<UnifiedDiffParser.ParsedDiffFile> changedFiles,
        List<AiReviewEngine.FindingProposal> findings,
        List<CodeIntelDtos.ChangeImpactResponse> impacts,
        TestSelectionService.TestSelectionPlan testPlan,
        ChangeRiskPolicyEntity policy
    ) {
        List<RiskFactor> factors = new ArrayList<>();
        Set<String> triggeredPolicies = new LinkedHashSet<>();
        boolean mustBlock = false;
        boolean mustWarn = false;
        int score = 0;

        int criticalSecurityCount = 0;
        int breakingApiCount = 0;
        int migrationRiskCount = 0;

        // 1. Evaluate deterministic findings by domain category & severity
        for (AiReviewEngine.FindingProposal f : findings) {
            int pts = switch (f.severity()) {
                case "CRITICAL" -> 32;
                case "HIGH" -> 18;
                case "MEDIUM" -> 9;
                default -> 4;
            };
            score += pts;

            String policyRule = "SCORE_WEIGHT_" + f.severity();
            if ("SECURITY".equalsIgnoreCase(f.category())) {
                if ("FORGE-PROMPT-001".equals(f.ruleId()) && policy.isBlockOnPromptInjection()) {
                    mustBlock = true;
                    policyRule = "POLICY_BLOCK_PROMPT_INJECTION";
                    triggeredPolicies.add(policyRule);
                } else if ("CRITICAL".equalsIgnoreCase(f.severity())) {
                    criticalSecurityCount++;
                    if (policy.isBlockOnCriticalSecurity()) {
                        mustBlock = true;
                        policyRule = "POLICY_BLOCK_CRITICAL_SECURITY";
                        triggeredPolicies.add(policyRule);
                    }
                } else if ("HIGH".equalsIgnoreCase(f.severity())) {
                    mustWarn = true;
                    policyRule = "POLICY_WARN_HIGH_SECURITY";
                    triggeredPolicies.add(policyRule);
                }
            } else if ("API_COMPATIBILITY".equalsIgnoreCase(f.category())) {
                breakingApiCount++;
                if ("CRITICAL".equalsIgnoreCase(f.severity()) && policy.isBlockOnBreakingApi()) {
                    mustBlock = true;
                    policyRule = "POLICY_BLOCK_BREAKING_API";
                    triggeredPolicies.add(policyRule);
                } else {
                    mustWarn = true;
                    policyRule = "POLICY_WARN_API_CONTRACT";
                    triggeredPolicies.add(policyRule);
                }
            } else if ("DB_MIGRATION".equalsIgnoreCase(f.category())) {
                migrationRiskCount++;
                if ("CRITICAL".equalsIgnoreCase(f.severity()) && policy.isBlockOnDestructiveMigration()) {
                    mustBlock = true;
                    policyRule = "POLICY_BLOCK_DESTRUCTIVE_MIGRATION";
                    triggeredPolicies.add(policyRule);
                } else {
                    mustWarn = true;
                    policyRule = "POLICY_WARN_DB_MIGRATION";
                    triggeredPolicies.add(policyRule);
                }
            }

            factors.add(new RiskFactor(
                f.category(),
                f.title(),
                f.severity(),
                pts,
                f.confidence(),
                f.filePath() + ":" + f.lineNumber() + " (" + f.ruleId() + ")",
                f.explanation(),
                policyRule
            ));
        }

        // 2. AST Dependency Blast Radius factor
        int totalImpacted = impacts.stream().mapToInt(CodeIntelDtos.ChangeImpactResponse::totalImpacted).sum();
        if (totalImpacted > 0) {
            int blastPts = Math.min(20, totalImpacted * 4);
            score += blastPts;
            String blastSeverity = totalImpacted >= 4 ? "HIGH" : (totalImpacted >= 2 ? "MEDIUM" : "LOW");
            String blastPolicy = totalImpacted >= 4 ? "POLICY_WARN_HIGH_BLAST_RADIUS" : "AST_BLAST_RADIUS";
            if (totalImpacted >= 4) {
                mustWarn = true;
                triggeredPolicies.add(blastPolicy);
            }
            List<String> affectedSample = impacts.stream()
                .flatMap(i -> i.impactedNodes().stream())
                .map(CodeIntelDtos.ImpactedNode::nodeName)
                .distinct()
                .limit(5)
                .toList();
            factors.add(new RiskFactor(
                "BLAST_RADIUS",
                "Downstream AST dependency impact (" + totalImpacted + " components)",
                blastSeverity,
                blastPts,
                0.90,
                "Impacted: " + String.join(", ", affectedSample),
                "Changes propagate across " + totalImpacted + " direct and transitive repository dependents.",
                blastPolicy
            ));
        }

        // 3. Sensitive Path Touchpoints factor (e.g. auth, payment, ledger, migration, crypto)
        List<String> sensitiveKeywords = parseStringList(policy.getSensitivePathsJson());
        List<String> touchedSensitive = changedFiles.stream()
            .map(UnifiedDiffParser.ParsedDiffFile::filePath)
            .filter(p -> {
                String lower = p.toLowerCase(Locale.ROOT);
                return sensitiveKeywords.stream().anyMatch(kw -> !kw.isBlank() && lower.contains(kw.toLowerCase(Locale.ROOT)));
            })
            .distinct()
            .toList();
        if (!touchedSensitive.isEmpty()) {
            int sensPts = Math.min(12, touchedSensitive.size() * 6);
            score += sensPts;
            factors.add(new RiskFactor(
                "SENSITIVE_SURFACE",
                "Modifies sensitive domain surface (" + touchedSensitive.size() + " files)",
                "MEDIUM",
                sensPts,
                0.95,
                String.join(", ", touchedSensitive),
                "Changed files match workspace sensitive path patterns requiring heightened peer review.",
                "POLICY_SENSITIVE_PATH_SURFACE"
            ));
        }

        // 4. Test Coverage Gap when high-impact production files change without test updates
        if (policy.isMinTestsForHighImpact() && totalImpacted >= 2 && testPlan != null && !testPlan.prIncludesTestChanges()) {
            int covPts = 8;
            score += covPts;
            mustWarn = true;
            triggeredPolicies.add("POLICY_WARN_MISSING_TESTS_FOR_HIGH_IMPACT");
            factors.add(new RiskFactor(
                "TEST_COVERAGE",
                "High-impact code modified without accompanying test changes",
                "MEDIUM",
                covPts,
                0.88,
                testPlan.recommendedCount() + " tests selected (NOT_EXECUTED)",
                "Production modules with " + totalImpacted + " downstream dependents were changed without updating tests in this PR.",
                "POLICY_WARN_MISSING_TESTS_FOR_HIGH_IMPACT"
            ));
        }

        int finalScore = Math.min(100, Math.max(0, score));

        // 5. Compute final deterministic merge decision from policy rules + score thresholds
        String decision;
        if (mustBlock || finalScore > policy.getMaxRiskScoreForWarn()) {
            decision = "BLOCK";
            if (!mustBlock) {
                triggeredPolicies.add("POLICY_BLOCK_RISK_SCORE_THRESHOLD (>" + policy.getMaxRiskScoreForWarn() + ")");
            }
        } else if (mustWarn || finalScore > policy.getMaxRiskScoreForPass()) {
            decision = "WARN";
            if (!mustWarn) {
                triggeredPolicies.add("POLICY_WARN_RISK_SCORE_THRESHOLD (>" + policy.getMaxRiskScoreForPass() + ")");
            }
        } else {
            decision = "PASS";
        }

        String summary = switch (decision) {
            case "BLOCK" -> "MERGE BLOCKED (Risk " + finalScore + "/100): Triggered blocking policy rules ["
                + String.join(", ", triggeredPolicies) + "]. Resolve critical issues before merging.";
            case "WARN" -> "MERGE CAUTION (Risk " + finalScore + "/100): Review " + factors.size()
                + " contributing risk factors and execute the " + (testPlan != null ? testPlan.recommendedCount() : 0)
                + " recommended tests before merging.";
            default -> "MERGE PASS (Risk " + finalScore + "/100): No configured blocking or warning conditions detected. "
                + PASS_SAFETY_DISCLAIMER;
        };

        return new ChangeRiskAssessment(
            finalScore,
            decision,
            policy.getVersion(),
            summary,
            PASS_SAFETY_DISCLAIMER,
            new ArrayList<>(triggeredPolicies),
            factors,
            totalImpacted,
            breakingApiCount,
            migrationRiskCount,
            criticalSecurityCount
        );
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }
}
