package com.forge.platform.review;

import com.forge.platform.ci.CiBuildEntity;
import com.forge.platform.ci.CiBuildRepository;
import com.forge.platform.codeintel.CodeIntelDtos;
import com.forge.platform.codeintel.CodeIntelligenceService;
import com.forge.platform.repository.SourceFileEntity;
import com.forge.platform.repository.SourceFileJpaRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Intelligent Test Selection Engine (Part 4E).
 * Maps changed files/symbols, reverse AST dependency callers, module integration tests,
 * and historical CI failures to concrete recommended tests with explicit rationales.
 *
 * Truth boundary: FORGE performs static test selection only and NEVER claims an unexecuted test passed.
 */
@Service
public class TestSelectionService {

    public static final String EXECUTION_STATUS_NOT_EXECUTED = "NOT_EXECUTED";
    public static final String DISCLAIMER =
        "FORGE selected these tests statically from changed symbols, dependency graph edges, and CI history. "
            + "These tests were NOT executed by FORGE; run them in CI before merging.";

    public record SelectedTest(
        String testName,
        String testFilePath,
        String selectionReason,
        String relatedChangedFile,
        String priority,
        String suggestedCommand,
        String executionStatus
    ) {}

    public record TestSelectionPlan(
        String executionStatus,
        String disclaimer,
        boolean prIncludesTestChanges,
        int recommendedCount,
        List<SelectedTest> selectedTests,
        List<String> suggestedCommands
    ) {}

    private final SourceFileJpaRepository sourceFileJpaRepository;
    private final CiBuildRepository ciBuildRepository;

    public TestSelectionService(
        SourceFileJpaRepository sourceFileJpaRepository,
        CiBuildRepository ciBuildRepository
    ) {
        this.sourceFileJpaRepository = sourceFileJpaRepository;
        this.ciBuildRepository = ciBuildRepository;
    }

    public TestSelectionPlan selectTests(
        String tenantId,
        String repositoryId,
        List<UnifiedDiffParser.ParsedDiffFile> changedFiles,
        List<CodeIntelDtos.ChangeImpactResponse> impacts
    ) {
        Map<String, SelectedTest> selected = new LinkedHashMap<>();

        boolean prTouchesTests = changedFiles.stream().anyMatch(f -> isTestPath(f.filePath()));

        List<SourceFileEntity> repoFiles = sourceFileJpaRepository
            .findByRepositoryIdAndTenantIdOrderByFilePathAsc(repositoryId, tenantId);
        List<SourceFileEntity> repoTestFiles = repoFiles.stream()
            .filter(f -> isTestPath(f.getFilePath()))
            .toList();

        // 1. Direct & module-level test matching from indexed repository test files
        for (UnifiedDiffParser.ParsedDiffFile changed : changedFiles) {
            if (isTestPath(changed.filePath())) {
                String testName = CodeIntelligenceService.extractSimpleName(changed.filePath());
                selected.putIfAbsent(changed.filePath(), new SelectedTest(
                    testName,
                    changed.filePath(),
                    "Test file directly modified in this pull request",
                    changed.filePath(),
                    "HIGH",
                    buildCommand(changed.filePath(), testName),
                    EXECUTION_STATUS_NOT_EXECUTED
                ));
                continue;
            }

            String changedSimple = CodeIntelligenceService.extractSimpleName(changed.filePath());
            String changedDir = parentDir(changed.filePath());

            for (SourceFileEntity tf : repoTestFiles) {
                String testSimple = CodeIntelligenceService.extractSimpleName(tf.getFilePath());
                if (testSimple.equalsIgnoreCase(changedSimple + "Test")
                    || testSimple.equalsIgnoreCase(changedSimple + "Tests")
                    || testSimple.equalsIgnoreCase(changedSimple + "IT")
                    || testSimple.equalsIgnoreCase(changedSimple + "Spec")
                    || testSimple.equalsIgnoreCase("test_" + changedSimple)) {
                    selected.putIfAbsent(tf.getFilePath(), new SelectedTest(
                        testSimple,
                        tf.getFilePath(),
                        "Direct unit/integration test targeting changed class/module `" + changedSimple + "`",
                        changed.filePath(),
                        "HIGH",
                        buildCommand(tf.getFilePath(), testSimple),
                        EXECUTION_STATUS_NOT_EXECUTED
                    ));
                } else if (testSimple.endsWith("IT") || testSimple.endsWith("IntegrationTest")) {
                    // Include relevant integration tests in matching module/package
                    String testDir = parentDir(tf.getFilePath()).replace("/test/", "/main/");
                    if (!changedDir.isEmpty() && (changedDir.contains(testDir) || testDir.contains(changedDir))) {
                        selected.putIfAbsent(tf.getFilePath(), new SelectedTest(
                            testSimple,
                            tf.getFilePath(),
                            "Integration test covering the same package/module as `" + changedSimple + "`",
                            changed.filePath(),
                            "MEDIUM",
                            buildCommand(tf.getFilePath(), testSimple),
                            EXECUTION_STATUS_NOT_EXECUTED
                        ));
                    }
                }
            }
        }

        // 2. Transitive test callers discovered via AST reverse dependency graph
        for (CodeIntelDtos.ChangeImpactResponse impact : impacts) {
            for (CodeIntelDtos.ImpactedNode node : impact.impactedNodes()) {
                if (node.isTest()) {
                    String path = node.filePath() != null && !node.filePath().isBlank()
                        ? node.filePath()
                        : node.nodeName();
                    selected.putIfAbsent(path, new SelectedTest(
                        node.nodeName(),
                        path,
                        "AST dependency graph shows `" + node.nodeName() + "` depends on modified `"
                            + impact.targetNode() + "` (" + node.impactReason() + ")",
                        impact.targetNode(),
                        node.hopDistance() == 1 ? "HIGH" : "MEDIUM",
                        buildCommand(path, node.nodeName()),
                        EXECUTION_STATUS_NOT_EXECUTED
                    ));
                }
            }
        }

        // 3. Historical CI failures referencing any changed symbol/file in this repository
        List<CiBuildEntity> recentBuilds = ciBuildRepository.findByTenantIdOrderByTriggeredAtDesc(tenantId);
        for (CiBuildEntity build : recentBuilds) {
            if (!repositoryId.equals(build.getRepositoryId()) || !"FAILED".equalsIgnoreCase(build.getStatus())) {
                continue;
            }
            String rawLog = build.getRawLog() != null ? build.getRawLog() : "";
            for (UnifiedDiffParser.ParsedDiffFile changed : changedFiles) {
                String changedSimple = CodeIntelligenceService.extractSimpleName(changed.filePath());
                if (changedSimple.length() >= 4 && rawLog.contains(changedSimple)) {
                    String key = "ci-history:" + build.getPipelineName();
                    selected.putIfAbsent(key, new SelectedTest(
                        build.getPipelineName(),
                        changed.filePath(),
                        "Historical CI failure on pipeline `" + build.getPipelineName() + "` referenced `"
                            + changedSimple + "` (" + build.getFailureCategory() + ")",
                        changed.filePath(),
                        "MEDIUM",
                        "Re-run CI workflow: " + build.getPipelineName(),
                        EXECUTION_STATUS_NOT_EXECUTED
                    ));
                }
            }
        }

        // 4. If a Java/TS source file has no existing test in repo, recommend creating/running conventional test target
        if (selected.isEmpty() && !changedFiles.isEmpty()) {
            UnifiedDiffParser.ParsedDiffFile primary = changedFiles.get(0);
            String simple = CodeIntelligenceService.extractSimpleName(primary.filePath());
            String suggestedPath = suggestMissingTestPath(primary.filePath(), simple);
            selected.put(suggestedPath, new SelectedTest(
                simple + "Test (Recommended New Coverage)",
                suggestedPath,
                "No existing test file matched changed `" + simple + "`; add unit tests before merge",
                primary.filePath(),
                "MEDIUM",
                buildCommand(suggestedPath, simple + "Test"),
                EXECUTION_STATUS_NOT_EXECUTED
            ));
        }

        List<SelectedTest> list = new ArrayList<>(selected.values());
        List<String> commands = list.stream()
            .map(SelectedTest::suggestedCommand)
            .distinct()
            .limit(6)
            .toList();

        return new TestSelectionPlan(
            EXECUTION_STATUS_NOT_EXECUTED,
            DISCLAIMER,
            prTouchesTests,
            list.size(),
            list,
            commands
        );
    }

    private boolean isTestPath(String path) {
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.contains("/test/") || lower.contains("/__tests__/")
            || lower.endsWith("test.java") || lower.endsWith("tests.java") || lower.endsWith("it.java")
            || lower.endsWith(".test.ts") || lower.endsWith(".test.tsx")
            || lower.endsWith(".spec.ts") || lower.endsWith(".spec.tsx")
            || lower.endsWith(".test.js") || lower.endsWith(".spec.js")
            || lower.contains("test_") && lower.endsWith(".py");
    }

    private String parentDir(String path) {
        String norm = path.replace('\\', '/');
        int idx = norm.lastIndexOf('/');
        return idx > 0 ? norm.substring(0, idx) : "";
    }

    private String suggestMissingTestPath(String sourcePath, String simple) {
        String norm = sourcePath.replace('\\', '/');
        if (norm.endsWith(".java")) {
            return norm.replace("/src/main/", "/src/test/").replace(".java", "Test.java");
        }
        if (norm.endsWith(".ts") || norm.endsWith(".tsx")) {
            return norm.replaceAll("\\.tsx?$", ".test.ts");
        }
        return "tests/" + simple + "Test";
    }

    private String buildCommand(String testPath, String testName) {
        String lower = testPath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            return "./mvnw test -Dtest=" + testName.replaceAll("\\s.*", "");
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".js") || lower.endsWith(".jsx")) {
            return "npx vitest run " + testPath;
        }
        if (lower.endsWith(".py")) {
            return "pytest " + testPath;
        }
        return "Run test suite for " + testPath;
    }
}
