package com.forge.platform.prediction;

import com.forge.platform.review.UnifiedDiffParser;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic Static Performance Regression Analyzer (Milestone 6).
 *
 * Detects high-confidence static performance anti-patterns in PR diffs:
 *   - N+1 database / repository query calls inside loops
 *   - Expensive / unindexed SQL patterns (SELECT *, leading wildcard LIKE '%...', unbounded findAll(), DROP INDEX)
 *   - Nested loop / quadratic algorithmic complexity patterns
 *   - Memory-intensive / unbounded payload reads (Files.readAllBytes, readAllLines, toByteArray)
 *   - Synchronous external HTTP/API calls or Thread.sleep inside loops / request paths
 *   - Cache invalidation or removal (removing @Cacheable, @CacheEvict(allEntries=true), FLUSHALL)
 *
 * Truth boundary: When live APM benchmarks or production telemetry are unavailable, FORGE ALWAYS
 * reports these as static performance warnings and NEVER fabricates latency/throughput numbers or ML labels.
 */
@Component
public class PerformanceRegressionAnalyzer {

    public static final String CATEGORY = "PERFORMANCE_REGRESSION";
    public static final String RULE_VERSION = "forge-perf-static-v1.0";

    private static final Pattern LOOP_OPEN = Pattern.compile(
        "\\b(for|while)\\s*\\(|\\.forEach\\s*\\(|\\.map\\s*\\("
    );
    private static final Pattern DB_CALL_PATTERN = Pattern.compile(
        "(?i)(Repository\\.(find|get|save|delete|count)|entityManager\\.|jdbcTemplate\\.|\\.(query|executeQuery|findOne|findUnique|findMany)\\s*\\(|SELECT\\s+.+\\s+FROM\\s+)"
    );
    private static final Pattern EXPENSIVE_QUERY_PATTERN = Pattern.compile(
        "(?i)(SELECT\\s+\\*\\s+FROM|LIKE\\s+['\"]%|\\.findAll\\s*\\(\\s*\\)|DROP\\s+INDEX|CREATE\\s+INDEX\\s+(?!CONCURRENTLY))"
    );
    private static final Pattern MEMORY_HEAVY_PATTERN = Pattern.compile(
        "(Files\\.readAllBytes|Files\\.readAllLines|\\.readAllBytes\\s*\\(|\\.toByteArray\\s*\\(|new\\s+byte\\s*\\[\\s*\\d{7,})"
    );
    private static final Pattern SYNC_EXTERNAL_CALL = Pattern.compile(
        "(restTemplate\\.|\\.block\\s*\\(|httpClient\\.send\\s*\\(|Thread\\.sleep\\s*\\(|\\.getForObject\\s*\\(|\\.exchange\\s*\\()"
    );
    private static final Pattern CACHE_EVICT_ALL = Pattern.compile(
        "(?i)(@CacheEvict\\s*\\([^)]*allEntries\\s*=\\s*true|FLUSHALL|FLUSHDB|cacheManager\\.getCache\\([^)]*\\)\\.clear\\s*\\()"
    );
    private static final Pattern LINEAR_IN_LOOP = Pattern.compile(
        "(Collections\\.sort\\s*\\(|\\.sort\\s*\\(|\\.contains\\s*\\()"
    );

    public PredictionCandidate.CategoryPredictionDraft analyze(PrFeatureExtractor.PrFeatureVector fv) {
        List<PredictionCandidate.EvidenceDraft> evidence = new ArrayList<>();
        List<PredictionDtos.TargetRiskBreakdown> targets = new ArrayList<>();
        int score = 0;

        for (UnifiedDiffParser.ParsedDiffFile file : fv.parsedFiles()) {
            String path = file.filePath();
            String lowerPath = path.toLowerCase(Locale.ROOT);
            // Skip test files and non-code documentation/config/asset files to prevent false positives
            if (!BugRiskPredictor.isCodeFile(path)
                || lowerPath.contains("/test/") || lowerPath.contains("/__tests__/")
                || lowerPath.endsWith("test.java") || lowerPath.endsWith(".test.ts")
                || lowerPath.endsWith(".test.tsx") || lowerPath.endsWith(".spec.ts")) {
                continue;
            }

            int fileScore = 0;
            List<String> fileWarnings = new ArrayList<>();

            // Check removed lines for cache removal (@Cacheable removed without replacement)
            boolean removedCacheable = file.removedLines().stream()
                .anyMatch(l -> l.content().contains("@Cacheable") || l.content().contains("cacheService.get"));
            boolean addedCacheable = file.addedLines().stream()
                .anyMatch(l -> l.content().contains("@Cacheable") || l.content().contains("cacheService.get"));
            if (removedCacheable && !addedCacheable) {
                int w = 18;
                score += w;
                fileScore += w;
                fileWarnings.add("Caching removed");
                evidence.add(new PredictionCandidate.EvidenceDraft(
                    "STATIC_PERF_WARNING",
                    "PERF_CACHE_REMOVED",
                    path,
                    "Caching annotation/lookup removed in " + path + " without replacement; may increase downstream DB/API load",
                    w,
                    0,
                    null,
                    "perf-cache-rm:" + path
                ));
            }

            // Track lexical loop nesting accurately using a brace-depth stack + per-file pattern deduplication
            int braceDepth = 0;
            Deque<Integer> loopEntryDepths = new ArrayDeque<>();
            boolean reportedNPlus1 = false;
            boolean reportedNestedLoop = false;
            boolean reportedSyncInLoop = false;
            boolean reportedSyncPath = false;
            boolean reportedExpensiveQuery = false;
            boolean reportedMemoryHeavy = false;
            boolean reportedCacheEvict = false;

            for (UnifiedDiffParser.DiffLine line : file.addedLines()) {
                String content = line.content();
                String trimmed = content.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue;
                }

                boolean opensLoop = LOOP_OPEN.matcher(content).find();
                int loopEntryDepth = braceDepth;
                if (opensLoop) {
                    loopEntryDepths.push(loopEntryDepth);
                    if (loopEntryDepths.size() >= 2 && !reportedNestedLoop) {
                        reportedNestedLoop = true;
                        int w = 18;
                        score += w;
                        fileScore += w;
                        fileWarnings.add("Nested loop O(n^2) risk at L" + line.lineNumber());
                        evidence.add(new PredictionCandidate.EvidenceDraft(
                            "STATIC_PERF_WARNING",
                            "PERF_NESTED_LOOP_COMPLEXITY",
                            path + ":" + line.lineNumber(),
                            "Nested loop detected at " + path + ":" + line.lineNumber()
                                + " — potential O(n^2) algorithmic complexity regression on large collections",
                            w,
                            0,
                            null,
                            "perf-algo:" + path + ":" + line.lineNumber()
                        ));
                    }
                }

                boolean insideLoop = !loopEntryDepths.isEmpty();

                if (insideLoop) {
                    if (!reportedNPlus1 && DB_CALL_PATTERN.matcher(content).find()) {
                        reportedNPlus1 = true;
                        int w = 28;
                        score += w;
                        fileScore += w;
                        fileWarnings.add("N+1 DB query inside loop at L" + line.lineNumber());
                        evidence.add(new PredictionCandidate.EvidenceDraft(
                            "STATIC_PERF_WARNING",
                            "PERF_N_PLUS_ONE_QUERY",
                            path + ":" + line.lineNumber(),
                            "Static warning: repository/database query executed inside loop at " + path + ":"
                                + line.lineNumber() + " (N+1 query pattern)",
                            w,
                            0,
                            null,
                            "perf-nplus1:" + path + ":" + line.lineNumber()
                        ));
                    }

                    if (!reportedSyncInLoop && SYNC_EXTERNAL_CALL.matcher(content).find()) {
                        reportedSyncInLoop = true;
                        int w = 24;
                        score += w;
                        fileScore += w;
                        fileWarnings.add("Blocking external call inside loop at L" + line.lineNumber());
                        evidence.add(new PredictionCandidate.EvidenceDraft(
                            "STATIC_PERF_WARNING",
                            "PERF_SYNC_IO_IN_LOOP",
                            path + ":" + line.lineNumber(),
                            "Static warning: synchronous external call or thread blocking inside loop at "
                                + path + ":" + line.lineNumber(),
                            w,
                            0,
                            null,
                            "perf-sync-loop:" + path + ":" + line.lineNumber()
                        ));
                    }

                    if (LINEAR_IN_LOOP.matcher(content).find() && !reportedNestedLoop) {
                        reportedNestedLoop = true;
                        int w = 14;
                        score += w;
                        fileScore += w;
                        fileWarnings.add("Sort/linear lookup inside loop at L" + line.lineNumber());
                        evidence.add(new PredictionCandidate.EvidenceDraft(
                            "STATIC_PERF_WARNING",
                            "PERF_LINEAR_OP_IN_LOOP",
                            path + ":" + line.lineNumber(),
                            "Static warning: sorting or linear collection search inside loop at " + path + ":" + line.lineNumber(),
                            w,
                            0,
                            null,
                            "perf-linear-loop:" + path + ":" + line.lineNumber()
                        ));
                    }
                } else if (!reportedSyncPath && SYNC_EXTERNAL_CALL.matcher(content).find() && fv.touchesCriticalRequestPath()) {
                    reportedSyncPath = true;
                    int w = 12;
                    score += w;
                    fileScore += w;
                    fileWarnings.add("Blocking external call on request path at L" + line.lineNumber());
                    evidence.add(new PredictionCandidate.EvidenceDraft(
                        "STATIC_PERF_WARNING",
                        "PERF_SYNC_EXTERNAL_REQUEST_PATH",
                        path + ":" + line.lineNumber(),
                        "Static warning: synchronous external API call or Thread.sleep on critical request path at "
                            + path + ":" + line.lineNumber(),
                        w,
                        0,
                        null,
                        "perf-sync-path:" + path + ":" + line.lineNumber()
                    ));
                }

                // Update lexical brace depth and pop any loops whose blocks just closed (or single-line loops without braces)
                boolean sawBraceOnLine = false;
                for (char ch : content.toCharArray()) {
                    if (ch == '{') {
                        braceDepth++;
                        sawBraceOnLine = true;
                    } else if (ch == '}') {
                        braceDepth = Math.max(0, braceDepth - 1);
                        sawBraceOnLine = true;
                        while (!loopEntryDepths.isEmpty() && braceDepth <= loopEntryDepths.peek()) {
                            loopEntryDepths.pop();
                        }
                    }
                }
                if (opensLoop && !sawBraceOnLine && trimmed.endsWith(";")) {
                    loopEntryDepths.pop();
                }

                if (!reportedExpensiveQuery && EXPENSIVE_QUERY_PATTERN.matcher(content).find()) {
                    reportedExpensiveQuery = true;
                    int w = 20;
                    score += w;
                    fileScore += w;
                    fileWarnings.add("Expensive/unindexed query pattern at L" + line.lineNumber());
                    evidence.add(new PredictionCandidate.EvidenceDraft(
                        "STATIC_PERF_WARNING",
                        "PERF_EXPENSIVE_DB_QUERY",
                        path + ":" + line.lineNumber(),
                        "Static warning: expensive/unbounded query or index-impacting DDL at " + path + ":" + line.lineNumber(),
                        w,
                        0,
                        null,
                        "perf-db:" + path + ":" + line.lineNumber()
                    ));
                }

                if (!reportedMemoryHeavy && MEMORY_HEAVY_PATTERN.matcher(content).find()) {
                    reportedMemoryHeavy = true;
                    int w = 16;
                    score += w;
                    fileScore += w;
                    fileWarnings.add("Unbounded memory load at L" + line.lineNumber());
                    evidence.add(new PredictionCandidate.EvidenceDraft(
                        "STATIC_PERF_WARNING",
                        "PERF_MEMORY_ALLOCATION",
                        path + ":" + line.lineNumber(),
                        "Static warning: full-payload in-memory buffering detected at " + path + ":" + line.lineNumber()
                            + "; prefer streaming for large inputs",
                        w,
                        0,
                        null,
                        "perf-mem:" + path + ":" + line.lineNumber()
                    ));
                }

                if (!reportedCacheEvict && CACHE_EVICT_ALL.matcher(content).find()) {
                    reportedCacheEvict = true;
                    int w = 16;
                    score += w;
                    fileScore += w;
                    fileWarnings.add("Full cache eviction at L" + line.lineNumber());
                    evidence.add(new PredictionCandidate.EvidenceDraft(
                        "STATIC_PERF_WARNING",
                        "PERF_BROAD_CACHE_INVALIDATION",
                        path + ":" + line.lineNumber(),
                        "Static warning: broad cache invalidation (allEntries/clear/FLUSH) at " + path + ":" + line.lineNumber()
                            + " can cause thundering-herd latency spikes",
                        w,
                        0,
                        null,
                        "perf-cache-evict:" + path + ":" + line.lineNumber()
                    ));
                }
            }

            if (fileScore > 0) {
                int clamped = Math.min(100, fileScore);
                targets.add(new PredictionDtos.TargetRiskBreakdown(
                    "FILE", path, path, BugRiskPredictor.toRiskLevel(clamped), clamped,
                    "Static performance warnings: " + String.join("; ", fileWarnings)
                ));
            }
        }

        int finalScore = Math.min(100, Math.max(0, score));
        String riskLevel = evidence.isEmpty() ? "LOW" : BugRiskPredictor.toRiskLevel(finalScore);
        String confidence = evidence.isEmpty() ? "LOW" : "MEDIUM";

        String explanation = evidence.isEmpty()
            ? "No static performance anti-patterns (N+1 queries, nested loops, unbounded reads, broad cache clears, or blocking calls in loops) were detected in this diff. Runtime telemetry is not connected."
            : "Static performance warning (" + riskLevel + ", " + finalScore + "/100): detected " + evidence.size()
                + " potential regression pattern(s) — "
                + evidence.stream().limit(3).map(PredictionCandidate.EvidenceDraft::summary)
                    .reduce((a, b) -> a + "; " + b).orElse("")
                + ".";

        String limitations = "No runtime benchmarks or production APM telemetry are connected. "
            + "These findings are deterministic static code warnings (NOT ML predictions and NOT measured latency regressions).";

        String action = evidence.isEmpty()
            ? "Run load/benchmark tests if modifying latency-sensitive paths."
            : "Batch DB queries (avoid N+1), paginate unbounded reads, add targeted DB indexes, and benchmark affected endpoints before production release.";

        return new PredictionCandidate.CategoryPredictionDraft(
            CATEGORY,
            finalScore,
            riskLevel,
            null, // Never fabricate performance regression probabilities or latency numbers
            confidence,
            false,
            0, // Zero telemetry benchmark samples
            null,
            RULE_VERSION,
            explanation,
            limitations,
            action,
            targets,
            evidence
        );
    }
}
