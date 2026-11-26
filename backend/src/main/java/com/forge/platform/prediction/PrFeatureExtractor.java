package com.forge.platform.prediction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.ci.CiBuildEntity;
import com.forge.platform.ci.CiBuildRepository;
import com.forge.platform.codeintel.CodeIntelDtos;
import com.forge.platform.codeintel.CodeIntelligenceService;
import com.forge.platform.codeintel.CodeSymbolEntity;
import com.forge.platform.codeintel.CodeSymbolRepository;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.review.ChangeRiskEngine;
import com.forge.platform.review.PullRequestReviewEntity;
import com.forge.platform.review.PullRequestReviewRepository;
import com.forge.platform.review.ReviewFindingEntity;
import com.forge.platform.review.ReviewFindingRepository;
import com.forge.platform.review.TestSelectionService;
import com.forge.platform.review.UnifiedDiffParser;
import com.forge.platform.security.SecurityFindingEntity;
import com.forge.platform.security.SecurityFindingRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts a structured, inspectable feature vector for a Pull Request review by reusing
 * FORGE's existing diff parser, AST symbols, dependency impact, security/contract findings,
 * CI builds, and persisted historical engineering outcomes (Milestone 3).
 *
 * Never fabricates missing historical records.
 */
@Component
public class PrFeatureExtractor {

    private static final Pattern AUTH_PATH_PATTERN = Pattern.compile(
        "(?i)(auth|security|jwt|oauth|rbac|permission|token|session|crypto|login|identity|acl)"
    );
    private static final Pattern CONFIG_PATH_PATTERN = Pattern.compile(
        "(?i)(application[-.\\w]*\\.ya?ml|application\\.properties|dockerfile|docker-compose|\\.github/workflows|helm|k8s|kubernetes|terraform|\\.tf$|nginx\\.conf|render\\.yaml|\\.env)"
    );
    private static final Pattern EXTERNAL_INTEGRATION_PATTERN = Pattern.compile(
        "(?i)(RestTemplate|WebClient|RestClient|HttpClient|FeignClient|OkHttpClient|fetch\\(|axios\\.|stripe|twilio|webhook|kafka|rabbit|sqs|sns|s3Client|github)"
    );
    private static final Pattern REQUEST_PATH_PATTERN = Pattern.compile(
        "(?i)(controller|endpoint|handler|filter|gateway|router|checkout|payment|settlement|order|auth)"
    );

    public record TestHistoryStats(
        String testName,
        String testFilePath,
        int totalRuns,
        int failedRuns,
        int flakyRuns,
        Instant lastFailureAt
    ) {}

    public record PrFeatureVector(
        String tenantId,
        String repositoryId,
        String repositoryName,
        String serviceName,
        String reviewId,
        int prNumber,
        String prTitle,
        String commitSha,
        List<UnifiedDiffParser.ParsedDiffFile> parsedFiles,
        List<String> changedFilePaths,
        Set<String> affectedModules,
        int totalAddedLines,
        int totalRemovedLines,
        int changeSizeLines,
        boolean prIncludesTestChanges,
        boolean touchesAuthOrSecurity,
        boolean touchesConfigOrInfra,
        boolean touchesExternalIntegration,
        boolean touchesDatabaseMigration,
        boolean touchesCriticalRequestPath,
        Map<String, Integer> fileMaxComplexity,
        int maxSymbolComplexity,
        double avgSymbolComplexity,
        List<CodeIntelDtos.ChangeImpactResponse> impacts,
        int directImpactedCount,
        int transitiveImpactedCount,
        int totalBlastRadius,
        Set<String> impactedModuleNames,
        List<ReviewFindingEntity> reviewFindings,
        List<SecurityFindingEntity> securityFindings,
        ChangeRiskEngine.ChangeRiskAssessment existingRiskAssessment,
        TestSelectionService.TestSelectionPlan testSelectionPlan,
        int breakingApiCount,
        int migrationRiskCount,
        int criticalSecurityCount,
        // Real Historical Evidence (zero fabricated values)
        int priorReviewsInRepo,
        Map<String, Integer> priorFileChangeCount,
        List<DefectRecordEntity> matchingDefects,
        int totalRepoDefects,
        List<HistoricalTestExecutionEntity> repoTestExecutions,
        List<HistoricalTestExecutionEntity> actualPrTestExecutions,
        Map<String, TestHistoryStats> testHistoryByName,
        List<CiBuildEntity> repoCiBuilds,
        List<CiBuildEntity> failedCiBuildsTouchingPr,
        List<DeploymentRecordEntity> serviceDeployments,
        long serviceFailedDeploymentCount,
        long serviceRollbackCount,
        List<IncidentRecordEntity> matchingIncidents,
        int totalRepoIncidents,
        int totalHistoricalSampleSize,
        Instant dataFreshnessAt,
        String dataAvailabilitySummary
    ) {
        public Map<String, Object> toSummaryMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("changedFilesCount", parsedFiles.size());
            map.put("changeSizeLines", changeSizeLines);
            map.put("affectedModules", new ArrayList<>(affectedModules));
            map.put("maxSymbolComplexity", maxSymbolComplexity);
            map.put("totalBlastRadius", totalBlastRadius);
            map.put("directImpactedCount", directImpactedCount);
            map.put("transitiveImpactedCount", transitiveImpactedCount);
            map.put("prIncludesTestChanges", prIncludesTestChanges);
            map.put("touchesAuthOrSecurity", touchesAuthOrSecurity);
            map.put("touchesConfigOrInfra", touchesConfigOrInfra);
            map.put("touchesExternalIntegration", touchesExternalIntegration);
            map.put("touchesDatabaseMigration", touchesDatabaseMigration);
            map.put("breakingApiCount", breakingApiCount);
            map.put("migrationRiskCount", migrationRiskCount);
            map.put("criticalSecurityCount", criticalSecurityCount);
            map.put("priorReviewsInRepo", priorReviewsInRepo);
            map.put("matchingDefectsCount", matchingDefects.size());
            map.put("repoTestExecutionsCount", repoTestExecutions.size());
            map.put("actualPrTestExecutionsCount", actualPrTestExecutions.size());
            map.put("repoCiBuildsCount", repoCiBuilds.size());
            map.put("serviceDeploymentsCount", serviceDeployments.size());
            map.put("serviceFailedDeploymentCount", serviceFailedDeploymentCount);
            map.put("serviceRollbackCount", serviceRollbackCount);
            map.put("matchingIncidentsCount", matchingIncidents.size());
            map.put("totalHistoricalSampleSize", totalHistoricalSampleSize);
            return map;
        }
    }

    private final UnifiedDiffParser diffParser;
    private final CodeSymbolRepository codeSymbolRepository;
    private final CodeIntelligenceService codeIntelligenceService;
    private final ReviewFindingRepository reviewFindingRepository;
    private final SecurityFindingRepository securityFindingRepository;
    private final PullRequestReviewRepository reviewRepository;
    private final TestSelectionService testSelectionService;
    private final CiBuildRepository ciBuildRepository;
    private final HistoricalTestExecutionRepository testExecutionRepository;
    private final DeploymentRecordRepository deploymentRecordRepository;
    private final IncidentRecordRepository incidentRecordRepository;
    private final DefectRecordRepository defectRecordRepository;
    private final ObjectMapper objectMapper;

    public PrFeatureExtractor(
        UnifiedDiffParser diffParser,
        CodeSymbolRepository codeSymbolRepository,
        CodeIntelligenceService codeIntelligenceService,
        ReviewFindingRepository reviewFindingRepository,
        SecurityFindingRepository securityFindingRepository,
        PullRequestReviewRepository reviewRepository,
        TestSelectionService testSelectionService,
        CiBuildRepository ciBuildRepository,
        HistoricalTestExecutionRepository testExecutionRepository,
        DeploymentRecordRepository deploymentRecordRepository,
        IncidentRecordRepository incidentRecordRepository,
        DefectRecordRepository defectRecordRepository,
        ObjectMapper objectMapper
    ) {
        this.diffParser = diffParser;
        this.codeSymbolRepository = codeSymbolRepository;
        this.codeIntelligenceService = codeIntelligenceService;
        this.reviewFindingRepository = reviewFindingRepository;
        this.securityFindingRepository = securityFindingRepository;
        this.reviewRepository = reviewRepository;
        this.testSelectionService = testSelectionService;
        this.ciBuildRepository = ciBuildRepository;
        this.testExecutionRepository = testExecutionRepository;
        this.deploymentRecordRepository = deploymentRecordRepository;
        this.incidentRecordRepository = incidentRecordRepository;
        this.defectRecordRepository = defectRecordRepository;
        this.objectMapper = objectMapper;
    }

    public PrFeatureVector extract(String tenantId, RepositoryEntity repo, PullRequestReviewEntity review) {
        List<UnifiedDiffParser.ParsedDiffFile> parsedFiles = diffParser.parse(review.getRawDiff());
        List<String> changedPaths = parsedFiles.stream().map(UnifiedDiffParser.ParsedDiffFile::filePath).toList();

        Set<String> affectedModules = new LinkedHashSet<>();
        int totalAdded = 0;
        int totalRemoved = 0;
        boolean touchesTests = false;
        boolean touchesAuth = false;
        boolean touchesConfig = false;
        boolean touchesExternal = false;
        boolean touchesMigration = false;
        boolean touchesCriticalPath = false;

        for (UnifiedDiffParser.ParsedDiffFile file : parsedFiles) {
            String path = file.filePath();
            String lowerPath = path.toLowerCase(Locale.ROOT);
            affectedModules.add(HistoricalDataIngestionService.deriveModuleName(null, path));
            totalAdded += file.addedLines().size();
            totalRemoved += file.removedLines().size();

            if (isTestPath(lowerPath)) touchesTests = true;
            if (AUTH_PATH_PATTERN.matcher(path).find()) touchesAuth = true;
            if (CONFIG_PATH_PATTERN.matcher(path).find()) touchesConfig = true;
            if (lowerPath.endsWith(".sql") || lowerPath.contains("migration") || lowerPath.contains("flyway") || lowerPath.contains("liquibase")) {
                touchesMigration = true;
            }
            if (REQUEST_PATH_PATTERN.matcher(path).find()) touchesCriticalPath = true;

            for (UnifiedDiffParser.DiffLine line : file.addedLines()) {
                if (EXTERNAL_INTEGRATION_PATTERN.matcher(line.content()).find()) {
                    touchesExternal = true;
                }
                if (AUTH_PATH_PATTERN.matcher(line.content()).find()
                    && (line.content().contains("@PreAuthorize") || line.content().contains("SecurityFilterChain")
                        || line.content().contains("permitAll") || line.content().contains("Jwt"))) {
                    touchesAuth = true;
                }
            }
        }

        // Reuse persisted AST symbol complexity from code_symbols
        List<CodeSymbolEntity> repoSymbols = codeSymbolRepository
            .findByRepositoryIdAndTenantIdOrderByFilePathAscStartLineAsc(repo.getId(), tenantId);
        Map<String, Integer> fileMaxComplexity = new LinkedHashMap<>();
        int maxComplexity = 0;
        int complexitySum = 0;
        int complexityCount = 0;
        for (UnifiedDiffParser.ParsedDiffFile file : parsedFiles) {
            int fileMax = 0;
            String normalizedPath = normalizePath(file.filePath());
            for (CodeSymbolEntity sym : repoSymbols) {
                String symPath = normalizePath(sym.getFilePath());
                if (symPath.equalsIgnoreCase(normalizedPath) || symPath.endsWith("/" + normalizedPath) || normalizedPath.endsWith("/" + symPath)) {
                    fileMax = Math.max(fileMax, sym.getComplexity());
                    complexitySum += sym.getComplexity();
                    complexityCount++;
                }
            }
            if (fileMax == 0) {
                // Estimate local added branch complexity from diff lines if file is not indexed yet
                fileMax = estimateDiffComplexity(file);
            }
            fileMaxComplexity.put(file.filePath(), fileMax);
            maxComplexity = Math.max(maxComplexity, fileMax);
        }
        double avgComplexity = complexityCount > 0
            ? Math.round(((double) complexitySum / complexityCount) * 10.0) / 10.0
            : (fileMaxComplexity.isEmpty() ? 0.0
                : Math.round(fileMaxComplexity.values().stream().mapToInt(Integer::intValue).average().orElse(0.0) * 10.0) / 10.0);

        // Reuse persisted ChangeImpactResponse from review (fallback to CodeIntelligenceService only if absent)
        List<CodeIntelDtos.ChangeImpactResponse> impacts = parseImpacts(review.getImpactedSymbolsJson());
        if (impacts.isEmpty() && !parsedFiles.isEmpty()) {
            impacts = new ArrayList<>();
            for (var f : parsedFiles) {
                try {
                    impacts.add(codeIntelligenceService.analyzeChangeImpactForTenant(tenantId, repo.getId(), f.filePath()));
                } catch (Exception ignored) {
                    // File may not exist in code index yet
                }
            }
        }

        int directImpacted = 0;
        int transitiveImpacted = 0;
        Set<String> impactedModules = new LinkedHashSet<>();
        for (CodeIntelDtos.ChangeImpactResponse imp : impacts) {
            for (CodeIntelDtos.ImpactedNode node : imp.impactedNodes()) {
                if (node.hopDistance() <= 1) directImpacted++;
                else transitiveImpacted++;
                if (node.filePath() != null && !node.filePath().isBlank()) {
                    impactedModules.add(HistoricalDataIngestionService.deriveModuleName(null, node.filePath()));
                }
            }
        }
        int totalBlastRadius = directImpacted + transitiveImpacted;

        // Reuse persisted review & security findings and ChangeRiskAssessment + TestSelectionPlan
        List<ReviewFindingEntity> reviewFindings = reviewFindingRepository
            .findByReviewIdAndTenantIdOrderBySeverityAscLineNumberAsc(review.getId(), tenantId);
        List<SecurityFindingEntity> securityFindings = securityFindingRepository
            .findByTenantIdAndRepositoryIdOrderByDetectedAtDesc(tenantId, repo.getId())
            .stream()
            .filter(sf -> review.getId().equals(sf.getReviewId()) && !"SUPPRESSED".equalsIgnoreCase(sf.getStatus()))
            .toList();

        ChangeRiskEngine.ChangeRiskAssessment riskAssessment = parseRiskAssessment(review.getRiskBreakdownJson());
        TestSelectionService.TestSelectionPlan testPlan = parseTestPlan(review.getRecommendedTestsJson());
        if (testPlan == null) {
            testPlan = testSelectionService.selectTests(tenantId, repo.getId(), parsedFiles, impacts);
        }

        int breakingApiCount = riskAssessment != null ? riskAssessment.breakingApiCount()
            : (int) reviewFindings.stream().filter(f -> "API_COMPATIBILITY".equalsIgnoreCase(f.getCategory())).count();
        int migrationRiskCount = riskAssessment != null ? riskAssessment.migrationRiskCount()
            : (int) reviewFindings.stream().filter(f -> "DB_MIGRATION".equalsIgnoreCase(f.getCategory())).count();
        int criticalSecurityCount = riskAssessment != null ? riskAssessment.criticalSecurityCount()
            : (int) securityFindings.stream().filter(f -> "CRITICAL".equalsIgnoreCase(f.getSeverity())).count();

        // --- Historical Data Aggregation (Strictly Real Records Only) ---
        List<PullRequestReviewEntity> priorReviews = reviewRepository
            .findByTenantIdAndRepositoryIdOrderByCreatedAtDesc(tenantId, repo.getId())
            .stream()
            .filter(r -> !r.getId().equals(review.getId()))
            .toList();

        Map<String, Integer> priorFileChangeCount = new HashMap<>();
        for (PullRequestReviewEntity prev : priorReviews) {
            String prevDiff = prev.getRawDiff() == null ? "" : prev.getRawDiff();
            for (String path : changedPaths) {
                String simple = CodeIntelligenceService.extractSimpleName(path);
                if (prevDiff.contains(path) || (!simple.isBlank() && prevDiff.contains(simple + "."))) {
                    priorFileChangeCount.merge(path, 1, Integer::sum);
                }
            }
        }

        List<DefectRecordEntity> repoDefects = defectRecordRepository
            .findByTenantIdAndRepositoryIdOrderByReportedAtDesc(tenantId, repo.getId());
        List<DefectRecordEntity> matchingDefects = repoDefects.stream()
            .filter(d -> defectMatchesChange(d, changedPaths, affectedModules))
            .toList();

        List<HistoricalTestExecutionEntity> repoTestExecs = testExecutionRepository
            .findByTenantIdAndRepositoryIdOrderByExecutedAtDesc(tenantId, repo.getId());
        List<HistoricalTestExecutionEntity> actualPrTests = testExecutionRepository
            .findByTenantIdAndRepositoryIdAndPrNumberOrderByExecutedAtDesc(tenantId, repo.getId(), review.getPrNumber());

        Map<String, TestHistoryStats> testHistoryByName = buildTestHistoryStats(repoTestExecs);

        List<CiBuildEntity> repoCiBuilds = ciBuildRepository
            .findByTenantIdAndRepositoryIdOrderByTriggeredAtDesc(tenantId, repo.getId());
        List<CiBuildEntity> failedCiBuildsTouchingPr = repoCiBuilds.stream()
            .filter(b -> "FAILED".equalsIgnoreCase(b.getStatus()) && ciBuildMatchesChange(b, changedPaths, affectedModules))
            .toList();

        String serviceName = repo.getName();
        List<DeploymentRecordEntity> repoDeployments = deploymentRecordRepository
            .findByTenantIdAndRepositoryIdOrderByDeployedAtDesc(tenantId, repo.getId());
        long serviceFailedDeployments = repoDeployments.stream()
            .filter(d -> "FAILED".equalsIgnoreCase(d.getOutcome()))
            .count();
        long serviceRollbacks = repoDeployments.stream()
            .filter(d -> "ROLLED_BACK".equalsIgnoreCase(d.getOutcome()))
            .count();

        List<IncidentRecordEntity> repoIncidents = incidentRecordRepository
            .findByTenantIdAndRepositoryIdOrderByOccurredAtDesc(tenantId, repo.getId());
        List<IncidentRecordEntity> matchingIncidents = repoIncidents.stream()
            .filter(inc -> incidentMatchesChange(inc, serviceName, affectedModules, impactedModules))
            .toList();

        int totalHistoricalSamples = repoDefects.size() + repoTestExecs.size() + repoCiBuilds.size()
            + repoDeployments.size() + repoIncidents.size() + priorReviews.size();

        Instant freshness = computeLatestTimestamp(
            repoDefects.stream().map(DefectRecordEntity::getReportedAt).toList(),
            repoTestExecs.stream().map(HistoricalTestExecutionEntity::getExecutedAt).toList(),
            repoCiBuilds.stream().map(CiBuildEntity::getTriggeredAt).toList(),
            repoDeployments.stream().map(DeploymentRecordEntity::getDeployedAt).toList(),
            repoIncidents.stream().map(IncidentRecordEntity::getOccurredAt).toList(),
            priorReviews.stream().map(PullRequestReviewEntity::getCreatedAt).toList()
        );

        String availabilitySummary = buildDataAvailabilitySummary(
            priorReviews.size(), repoDefects.size(), repoTestExecs.size(),
            repoCiBuilds.size(), repoDeployments.size(), repoIncidents.size()
        );

        return new PrFeatureVector(
            tenantId,
            repo.getId(),
            repo.getFullName(),
            serviceName,
            review.getId(),
            review.getPrNumber(),
            review.getTitle(),
            review.getCommitSha(),
            parsedFiles,
            changedPaths,
            affectedModules,
            totalAdded,
            totalRemoved,
            totalAdded + totalRemoved,
            touchesTests,
            touchesAuth,
            touchesConfig,
            touchesExternal,
            touchesMigration,
            touchesCriticalPath,
            fileMaxComplexity,
            maxComplexity,
            avgComplexity,
            impacts,
            directImpacted,
            transitiveImpacted,
            totalBlastRadius,
            impactedModules,
            reviewFindings,
            securityFindings,
            riskAssessment,
            testPlan,
            breakingApiCount,
            migrationRiskCount,
            criticalSecurityCount,
            priorReviews.size(),
            priorFileChangeCount,
            matchingDefects,
            repoDefects.size(),
            repoTestExecs,
            actualPrTests,
            testHistoryByName,
            repoCiBuilds,
            failedCiBuildsTouchingPr,
            repoDeployments,
            serviceFailedDeployments,
            serviceRollbacks,
            matchingIncidents,
            repoIncidents.size(),
            totalHistoricalSamples,
            freshness,
            availabilitySummary
        );
    }

    private boolean defectMatchesChange(DefectRecordEntity defect, List<String> changedPaths, Set<String> modules) {
        if (defect.getModuleName() != null && modules.stream().anyMatch(m -> m.equalsIgnoreCase(defect.getModuleName()))) {
            return true;
        }
        List<String> defectFiles = parseStringList(defect.getFilePathsJson());
        for (String df : defectFiles) {
            String normDf = normalizePath(df);
            for (String cp : changedPaths) {
                String normCp = normalizePath(cp);
                if (normCp.equalsIgnoreCase(normDf) || normCp.endsWith("/" + normDf) || normDf.endsWith("/" + normCp)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean ciBuildMatchesChange(CiBuildEntity build, List<String> changedPaths, Set<String> modules) {
        String log = build.getRawLog() == null ? "" : build.getRawLog();
        for (String path : changedPaths) {
            String simple = CodeIntelligenceService.extractSimpleName(path);
            if (simple.length() >= 4 && log.contains(simple)) return true;
        }
        for (String m : modules) {
            if (m.length() >= 4 && log.toLowerCase(Locale.ROOT).contains(m.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private boolean incidentMatchesChange(
        IncidentRecordEntity inc, String serviceName, Set<String> affectedModules, Set<String> impactedModules
    ) {
        List<String> incModules = parseStringList(inc.getAffectedModulesJson());
        for (String im : incModules) {
            if (affectedModules.stream().anyMatch(m -> m.equalsIgnoreCase(im))
                || impactedModules.stream().anyMatch(m -> m.equalsIgnoreCase(im))) {
                return true;
            }
        }
        return inc.getServiceName() != null && inc.getServiceName().equalsIgnoreCase(serviceName);
    }

    private Map<String, TestHistoryStats> buildTestHistoryStats(List<HistoricalTestExecutionEntity> execs) {
        Map<String, List<HistoricalTestExecutionEntity>> grouped = new LinkedHashMap<>();
        for (HistoricalTestExecutionEntity e : execs) {
            grouped.computeIfAbsent(e.getTestName(), k -> new ArrayList<>()).add(e);
        }
        Map<String, TestHistoryStats> stats = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            List<HistoricalTestExecutionEntity> runs = entry.getValue();
            int failed = (int) runs.stream().filter(r -> "FAILED".equalsIgnoreCase(r.getOutcome())).count();
            int flaky = (int) runs.stream().filter(r -> "FLAKY".equalsIgnoreCase(r.getOutcome())).count();
            Instant lastFail = runs.stream()
                .filter(r -> "FAILED".equalsIgnoreCase(r.getOutcome()) || "FLAKY".equalsIgnoreCase(r.getOutcome()))
                .map(HistoricalTestExecutionEntity::getExecutedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
            String path = runs.stream().map(HistoricalTestExecutionEntity::getTestFilePath)
                .filter(p -> p != null && !p.isBlank()).findFirst().orElse(null);
            stats.put(entry.getKey(), new TestHistoryStats(entry.getKey(), path, runs.size(), failed, flaky, lastFail));
        }
        return stats;
    }

    private int estimateDiffComplexity(UnifiedDiffParser.ParsedDiffFile file) {
        int branches = 1;
        for (UnifiedDiffParser.DiffLine line : file.addedLines()) {
            String c = line.content();
            if (c.contains("if (") || c.contains("if(") || c.contains("for (") || c.contains("for(")
                || c.contains("while (") || c.contains("catch (") || c.contains("case ")
                || c.contains("&&") || c.contains("||") || c.contains(" ? ")) {
                branches++;
            }
        }
        return branches;
    }

    private boolean isTestPath(String lowerPath) {
        return lowerPath.contains("/test/") || lowerPath.contains("/__tests__/")
            || lowerPath.endsWith("test.java") || lowerPath.endsWith("tests.java") || lowerPath.endsWith("it.java")
            || lowerPath.endsWith(".test.ts") || lowerPath.endsWith(".test.tsx")
            || lowerPath.endsWith(".spec.ts") || lowerPath.endsWith(".spec.tsx");
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').trim();
    }

    @SafeVarargs
    private Instant computeLatestTimestamp(List<Instant>... lists) {
        Instant latest = null;
        for (List<Instant> list : lists) {
            for (Instant ts : list) {
                if (ts != null && (latest == null || ts.isAfter(latest))) {
                    latest = ts;
                }
            }
        }
        return latest;
    }

    private String buildDataAvailabilitySummary(
        int priorPrs, int defects, int tests, int ciBuilds, int deployments, int incidents
    ) {
        List<String> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (priorPrs > 0) present.add(priorPrs + " prior PR reviews"); else missing.add("prior PR history");
        if (defects > 0) present.add(defects + " defect records"); else missing.add("defect records");
        if (tests > 0) present.add(tests + " test executions"); else missing.add("per-test execution history");
        if (ciBuilds > 0) present.add(ciBuilds + " CI builds"); else missing.add("CI pipeline history");
        if (deployments > 0) present.add(deployments + " deployments"); else missing.add("deployment/rollback history");
        if (incidents > 0) present.add(incidents + " confirmed incidents"); else missing.add("confirmed incident history");

        if (present.isEmpty()) {
            return "No historical engineering outcomes recorded for this repository yet. "
                + "Predictions are deterministic structural baselines based strictly on current PR diff, AST, and dependency signals.";
        }
        if (missing.isEmpty()) {
            return "Full historical signal coverage available: " + String.join(", ", present) + ".";
        }
        return "Partial historical coverage (" + String.join(", ", present) + "). Limited/unavailable sources: "
            + String.join(", ", missing) + ".";
    }

    private List<CodeIntelDtos.ChangeImpactResponse> parseImpacts(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<CodeIntelDtos.ChangeImpactResponse>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private ChangeRiskEngine.ChangeRiskAssessment parseRiskAssessment(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, ChangeRiskEngine.ChangeRiskAssessment.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private TestSelectionService.TestSelectionPlan parseTestPlan(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, TestSelectionService.TestSelectionPlan.class);
        } catch (Exception ex) {
            return null;
        }
    }

    List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }
}
