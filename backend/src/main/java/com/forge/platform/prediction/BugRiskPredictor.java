package com.forge.platform.prediction;

import com.forge.platform.codeintel.CodeIntelligenceService;
import com.forge.platform.review.UnifiedDiffParser;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic Baseline Bug Risk Predictor (Milestone 4A).
 *
 * Evaluates defect likelihood using historical defect records, file churn, AST symbol complexity,
 * change size, dependency blast radius, missing test coverage, and overlapping historical incidents.
 *
 * Truth boundary: High predicted risk indicates elevated defect susceptibility based on evidence —
 * it NEVER asserts that a file definitively contains a bug.
 */
@Component
public class BugRiskPredictor {

    public static final String CATEGORY = "BUG_RISK";
    public static final String RULE_VERSION = "forge-bug-baseline-v1.0";

    private static final Set<String> CODE_EXTENSIONS = Set.of(
        ".java", ".kt", ".kts", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs",
        ".py", ".go", ".rs", ".rb", ".cs", ".cpp", ".cc", ".c", ".h", ".php", ".scala", ".swift", ".sql"
    );
    private static final Set<String> AST_SUPPORTED_EXTENSIONS = Set.of(
        ".java", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"
    );

    public PredictionCandidate.CategoryPredictionDraft predict(PrFeatureExtractor.PrFeatureVector fv) {
        List<PredictionCandidate.EvidenceDraft> evidence = new ArrayList<>();
        List<PredictionDtos.TargetRiskBreakdown> targets = new ArrayList<>();

        int score = 0;
        int historicalSamples = fv.totalRepoDefects() + fv.priorReviewsInRepo() + fv.totalRepoIncidents();

        // 1. Historical defect records overlapping changed files or modules
        if (!fv.matchingDefects().isEmpty()) {
            int defectWeight = Math.min(34, fv.matchingDefects().size() * 14);
            score += defectWeight;
            Instant latestDefect = fv.matchingDefects().stream()
                .map(DefectRecordEntity::getReportedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "HISTORICAL_DEFECTS",
                "DEFECT_HISTORY_MATCH",
                String.join(", ", fv.affectedModules()),
                fv.matchingDefects().size() + " confirmed historical defect(s) overlap changed files/modules ("
                    + fv.matchingDefects().get(0).getTitle() + ")",
                defectWeight,
                fv.matchingDefects().size(),
                latestDefect,
                "defect-match:" + String.join(",", fv.affectedModules())
            ));
        }

        // 2. Prior file churn across earlier PR reviews
        int maxChurn = fv.priorFileChangeCount().values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (maxChurn >= 2) {
            int churnWeight = Math.min(18, maxChurn * 5);
            score += churnWeight;
            String hotFile = fv.priorFileChangeCount().entrySet().stream()
                .max(Comparator.comparingInt(java.util.Map.Entry::getValue))
                .map(java.util.Map.Entry::getKey)
                .orElse(fv.changedFilePaths().isEmpty() ? "repository" : fv.changedFilePaths().get(0));
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "HISTORICAL_PR_CHURN",
                "FREQUENTLY_CHANGED_FILE",
                hotFile,
                hotFile + " was modified in " + maxChurn + " prior PR reviews in this repository",
                churnWeight,
                maxChurn,
                fv.dataFreshnessAt(),
                "churn:" + hotFile
            ));
        }

        // 3. Code complexity (AST symbol complexity or diff branch estimate)
        if (fv.maxSymbolComplexity() >= 8) {
            int cxWeight = fv.maxSymbolComplexity() >= 15 ? 18 : 11;
            score += cxWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "AST_COMPLEXITY",
                "HIGH_CYCLOMATIC_COMPLEXITY",
                "maxComplexity=" + fv.maxSymbolComplexity(),
                "Modified code reaches cyclomatic complexity " + fv.maxSymbolComplexity()
                    + " (avg " + fv.avgSymbolComplexity() + "), increasing defect susceptibility",
                cxWeight,
                0,
                null,
                "complexity:" + fv.reviewId()
            ));
        }

        // 4. Change size
        if (fv.changeSizeLines() >= 80) {
            int sizeWeight = fv.changeSizeLines() >= 250 ? 18 : 10;
            score += sizeWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "DIFF_SIZE",
                "LARGE_CHANGE_SET",
                fv.parsedFiles().size() + " files / " + fv.changeSizeLines() + " lines",
                "Large diff (" + fv.totalAddedLines() + " added, " + fv.totalRemovedLines()
                    + " removed across " + fv.parsedFiles().size() + " files) increases regression surface",
                sizeWeight,
                0,
                null,
                "diff-size:" + fv.reviewId()
            ));
        }

        // 5. Dependency blast radius
        if (fv.totalBlastRadius() > 0) {
            int blastWeight = Math.min(18, fv.totalBlastRadius() * 4);
            score += blastWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "DEPENDENCY_GRAPH",
                "BLAST_RADIUS_PROPAGATION",
                fv.totalBlastRadius() + " downstream nodes",
                "Change affects " + fv.directImpactedCount() + " direct and "
                    + fv.transitiveImpactedCount() + " transitive dependent components",
                blastWeight,
                0,
                null,
                "blast-radius:" + fv.reviewId()
            ));
        }

        // 6. Missing test coverage in PR (strictly for production code files — never docs, images, or lockfiles)
        boolean hasProdCode = fv.changedFilePaths().stream().anyMatch(BugRiskPredictor::isProductionCodeFile);
        if (hasProdCode && !fv.prIncludesTestChanges()) {
            int covWeight = 12;
            score += covWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "TEST_COVERAGE_GAP",
                "NO_TEST_UPDATES_IN_PR",
                "testFilesModified=0",
                "Production code modified without accompanying automated test additions or updates in this PR",
                covWeight,
                0,
                null,
                "missing-tests:" + fv.reviewId()
            ));
        }

        // 7. Overlapping confirmed historical incidents (correlated context only)
        if (!fv.matchingIncidents().isEmpty()) {
            int incWeight = Math.min(14, fv.matchingIncidents().size() * 7);
            score += incWeight;
            Instant latestIncident = fv.matchingIncidents().stream()
                .map(IncidentRecordEntity::getOccurredAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "HISTORICAL_INCIDENTS",
                "OVERLAPPING_INCIDENT_MODULE",
                fv.serviceName(),
                fv.matchingIncidents().size() + " prior confirmed incident(s) involved overlapping modules/services",
                incWeight,
                fv.matchingIncidents().size(),
                latestIncident,
                "incident-overlap:" + fv.serviceName()
            ));
        }

        // Build per-file, per-class, per-method, and per-module breakdowns
        List<String> unsupportedAstCodeFiles = new ArrayList<>();
        for (UnifiedDiffParser.ParsedDiffFile file : fv.parsedFiles()) {
            String filePath = file.filePath();
            boolean isCodeFile = isCodeFile(filePath);
            if (isCodeFile && !isAstSupportedFile(filePath)) {
                unsupportedAstCodeFiles.add(filePath);
            }

            int fileScore = 0;
            List<String> reasons = new ArrayList<>();
            int fileCx = fv.fileMaxComplexity().getOrDefault(filePath, 1);
            if (isCodeFile && fileCx >= 8) {
                fileScore += 25;
                reasons.add("complexity=" + fileCx);
            }
            int fileChurn = fv.priorFileChangeCount().getOrDefault(filePath, 0);
            if (fileChurn >= 1) {
                fileScore += Math.min(25, fileChurn * 10);
                reasons.add("prior PR changes=" + fileChurn);
            }
            long fileDefects = fv.matchingDefects().stream()
                .filter(d -> defectMatchesExactFile(d.getFilePathsJson(), filePath))
                .count();
            if (fileDefects > 0) {
                fileScore += Math.min(35, (int) fileDefects * 18);
                reasons.add(fileDefects + " prior defect(s)");
            }
            int lines = file.addedLines().size() + file.removedLines().size();
            if (isCodeFile && lines >= 40) {
                fileScore += Math.min(20, lines / 6);
                reasons.add(lines + " lines changed");
            }
            if (!fv.prIncludesTestChanges() && isProductionCodeFile(filePath)) {
                fileScore += 10;
                reasons.add("no test changes in PR");
            }
            fileScore = Math.min(100, Math.max(isCodeFile ? 5 : 0, fileScore));
            String fileLevel = toRiskLevel(fileScore);
            String reasonText = !isCodeFile
                ? "Non-code/documentation or asset file (excluded from production-code complexity & missing-test penalties)"
                : reasons.isEmpty()
                    ? "Small isolated change with low structural defect indicators"
                    : "Elevated defect susceptibility: " + String.join(", ", reasons);

            targets.add(new PredictionDtos.TargetRiskBreakdown(
                "FILE", filePath, filePath, fileLevel, fileScore, reasonText
            ));

            String simpleClass = CodeIntelligenceService.extractSimpleName(filePath);
            if (isCodeFile && !simpleClass.isBlank()) {
                targets.add(new PredictionDtos.TargetRiskBreakdown(
                    "CLASS", simpleClass, filePath, fileLevel, fileScore, reasonText
                ));
            }
            for (String sym : file.changedSymbols().stream().limit(4).toList()) {
                targets.add(new PredictionDtos.TargetRiskBreakdown(
                    "METHOD", simpleClass + "." + sym, filePath, fileLevel, fileScore,
                    "Modified symbol in " + fileLevel.toLowerCase() + "-risk file (" + reasonText + ")"
                ));
            }
        }

        int finalScore = Math.min(100, Math.max(0, score));
        boolean insufficientHistory = fv.totalRepoDefects() == 0 && fv.priorReviewsInRepo() < 3;
        String riskLevel = toRiskLevel(finalScore);

        for (String mod : fv.affectedModules()) {
            targets.add(new PredictionDtos.TargetRiskBreakdown(
                "MODULE", mod, null, riskLevel, finalScore,
                "Module touched by PR (" + fv.matchingDefects().size() + " overlapping defects)"
            ));
        }

        String confidence = assessConfidence(historicalSamples, !fv.matchingDefects().isEmpty(), !evidence.isEmpty());
        String explanation = buildExplanation(riskLevel, finalScore, insufficientHistory, evidence, fv);
        String unsupportedNote = unsupportedAstCodeFiles.isEmpty()
            ? ""
            : " Note: " + unsupportedAstCodeFiles.size()
                + " changed file(s) use languages outside Java/TS/JS AST indexing (diff-level heuristics only).";
        String limitations = (insufficientHistory
            ? PredictionDtos.INSUFFICIENT_DATA_MESSAGE + " Defect history for this repository is empty or minimal ("
                + fv.totalRepoDefects() + " defects, " + fv.priorReviewsInRepo()
                + " prior PRs); score reflects a deterministic structural baseline only and does not assert a bug exists."
            : "Deterministic baseline over " + historicalSamples + " historical records; elevated risk indicates heightened defect "
                + "susceptibility and does not prove that a bug is present. Probabilities are omitted until calibrated on held-out outcomes.")
            + unsupportedNote;

        String action = switch (riskLevel) {
            case "CRITICAL", "HIGH" -> "Assign targeted peer review to top-risk files/methods, add unit tests covering modified branches, and verify edge cases before merge.";
            case "MEDIUM" -> "Review high-complexity hunks and confirm automated tests exercise modified paths.";
            default -> "Standard peer review and CI test verification are appropriate.";
        };

        return new PredictionCandidate.CategoryPredictionDraft(
            CATEGORY,
            finalScore,
            riskLevel,
            null, // Never emit uncalibrated probability
            confidence,
            insufficientHistory,
            historicalSamples,
            fv.dataFreshnessAt(),
            RULE_VERSION,
            explanation,
            limitations,
            action,
            targets,
            evidence
        );
    }

    private String assessConfidence(int historicalSamples, boolean hasDefectMatch, boolean hasStructuralEvidence) {
        if (historicalSamples >= 10 && hasDefectMatch) return "HIGH";
        if (historicalSamples >= 3 || (hasDefectMatch && hasStructuralEvidence)) return "MEDIUM";
        if (hasStructuralEvidence) return "LOW";
        return "INSUFFICIENT_DATA";
    }

    private String buildExplanation(
        String level, int score, boolean insufficientHistory,
        List<PredictionCandidate.EvidenceDraft> evidence, PrFeatureExtractor.PrFeatureVector fv
    ) {
        if (evidence.isEmpty()) {
            return insufficientHistory
                ? PredictionDtos.INSUFFICIENT_DATA_MESSAGE + " Minimal structural change (" + fv.changeSizeLines()
                    + " lines) with no historical defect records."
                : "Low predicted defect risk (" + score + "/100): compact change with no historical defect hotspots detected.";
        }
        String topSignals = evidence.stream().limit(3).map(PredictionCandidate.EvidenceDraft::summary).reduce((a, b) -> a + "; " + b).orElse("");
        String prefix = insufficientHistory
            ? "Structural baseline (" + level + ", " + score + "/100 — limited defect history): "
            : level + " predicted defect risk (" + score + "/100): ";
        return prefix + topSignals + ". Note: high risk indicates susceptibility, not confirmed defect presence.";
    }

    static String toRiskLevel(int score) {
        if (score >= 70) return "CRITICAL";
        if (score >= 45) return "HIGH";
        if (score >= 22) return "MEDIUM";
        return "LOW";
    }

    static boolean isCodeFile(String path) {
        if (path == null || path.isBlank()) return false;
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String ext : CODE_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    static boolean isAstSupportedFile(String path) {
        if (path == null || path.isBlank()) return false;
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String ext : AST_SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    static boolean isProductionCodeFile(String path) {
        if (!isCodeFile(path)) return false;
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return !lower.contains("/test/") && !lower.contains("/__tests__/")
            && !lower.endsWith("test.java") && !lower.endsWith("tests.java") && !lower.endsWith("it.java")
            && !lower.endsWith(".test.ts") && !lower.endsWith(".test.tsx")
            && !lower.endsWith(".spec.ts") && !lower.endsWith(".spec.tsx");
    }

    static boolean defectMatchesExactFile(String filePathsJson, String targetPath) {
        if (filePathsJson == null || filePathsJson.isBlank() || targetPath == null || targetPath.isBlank()) {
            return false;
        }
        String normTarget = targetPath.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
        String trimmed = filePathsJson.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        for (String token : trimmed.split(",")) {
            String candidate = token.trim().replaceAll("^\"|\"$", "").replace('\\', '/').toLowerCase(Locale.ROOT);
            if (candidate.isBlank()) continue;
            if (candidate.equals(normTarget)
                || normTarget.endsWith("/" + candidate)
                || candidate.endsWith("/" + normTarget)) {
                return true;
            }
        }
        return false;
    }
}
