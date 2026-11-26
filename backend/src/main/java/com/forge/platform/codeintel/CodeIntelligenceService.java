package com.forge.platform.codeintel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditService;
import com.forge.platform.common.TenantContext;
import com.forge.platform.messaging.DomainEventHandler;
import com.forge.platform.messaging.ForgeTopics;
import com.forge.platform.messaging.IdempotencyService;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.RepositoryJpaRepository;
import com.forge.platform.repository.RepositoryService;
import com.forge.platform.repository.SourceFileEntity;
import com.forge.platform.repository.SourceFileJpaRepository;
import com.forge.platform.sse.PipelineEvent;
import com.forge.platform.sse.SseHubService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Service
public class CodeIntelligenceService implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CodeIntelligenceService.class);

    private final RepositoryJpaRepository repositoryJpaRepository;
    private final SourceFileJpaRepository sourceFileJpaRepository;
    private final CodeSymbolRepository codeSymbolRepository;
    private final CodeDependencyRepository codeDependencyRepository;
    private final RepositoryService repositoryService;
    private final JavaAstAnalyzer astAnalyzer;
    private final IdempotencyService idempotencyService;
    private final SseHubService sseHubService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public CodeIntelligenceService(
        RepositoryJpaRepository repositoryJpaRepository,
        SourceFileJpaRepository sourceFileJpaRepository,
        CodeSymbolRepository codeSymbolRepository,
        CodeDependencyRepository codeDependencyRepository,
        RepositoryService repositoryService,
        JavaAstAnalyzer astAnalyzer,
        IdempotencyService idempotencyService,
        SseHubService sseHubService,
        AuditService auditService,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.sourceFileJpaRepository = sourceFileJpaRepository;
        this.codeSymbolRepository = codeSymbolRepository;
        this.codeDependencyRepository = codeDependencyRepository;
        this.repositoryService = repositoryService;
        this.astAnalyzer = astAnalyzer;
        this.idempotencyService = idempotencyService;
        this.sseHubService = sseHubService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean supports(String topic) {
        return ForgeTopics.REPO_INDEX_REQUESTED.equals(topic);
    }

    @Override
    public void handle(String eventId, String tenantId, String payload) {
        String claimKey = "repo-index:" + eventId;
        if (!idempotencyService.tryClaimEvent(claimKey, tenantId, "REPO_INDEX_REQUESTED")) {
            return;
        }
        String repoId = "";
        try {
            JsonNode node = objectMapper.readTree(payload);
            repoId = node.path("repositoryId").asText();
            final String targetRepoId = repoId;
            transactionTemplate.executeWithoutResult(status -> indexRepository(tenantId, targetRepoId));
            idempotencyService.markCompleted(claimKey);
        } catch (Exception ex) {
            idempotencyService.releaseClaim(claimKey);
            markIndexFailed(tenantId, repoId, ex.getMessage());
            log.error("Async repository indexing failed for event {}: {}", eventId, ex.getMessage(), ex);
            throw new IllegalStateException("Repository indexing failed for event " + eventId, ex);
        }
    }

    private void markIndexFailed(String tenantId, String repositoryId, String errorMsg) {
        if (repositoryId == null || repositoryId.isBlank()) return;
        try {
            transactionTemplate.executeWithoutResult(status ->
                repositoryJpaRepository.findByIdAndTenantId(repositoryId, tenantId).ifPresent(repo -> {
                    repo.setIndexStatus("INDEX_FAILED");
                    repositoryJpaRepository.save(repo);
                })
            );
            sseHubService.publish(tenantId, PipelineEvent.of(
                "REPO_INDEX", repositoryId, "FAILED", 100,
                "Repository AST indexing failed: " + errorMsg
            ));
        } catch (Exception ignored) {
            // best effort status update
        }
    }

    @Transactional
    public void indexRepository(String tenantId, String repositoryId) {
        RepositoryEntity repo = repositoryJpaRepository.findByIdAndTenantId(repositoryId, tenantId).orElse(null);
        if (repo == null) return;

        sseHubService.publish(tenantId, PipelineEvent.of(
            "REPO_INDEX", repositoryId, "PARSING_AST", 30,
            "Parsing JavaParser & TypeScript/JS AST for " + repo.getFullName()
        ));

        List<SourceFileEntity> files = sourceFileJpaRepository
            .findByRepositoryIdAndTenantIdOrderByFilePathAsc(repositoryId, tenantId);

        codeSymbolRepository.deleteByRepositoryIdAndTenantId(repositoryId, tenantId);
        codeDependencyRepository.deleteByRepositoryIdAndTenantId(repositoryId, tenantId);

        // Preserve duplicate basenames (e.g. billing/Config.java vs auth/Config.java) using disambiguated node keys!
        Map<String, List<SourceFileEntity>> filesBySimpleName = new HashMap<>();
        for (SourceFileEntity f : files) {
            filesBySimpleName.computeIfAbsent(extractSimpleName(f.getFilePath()), k -> new ArrayList<>()).add(f);
        }

        Map<String, String> filePathToNodeKey = new HashMap<>();
        Map<String, List<String>> simpleNameToNodeKeys = new HashMap<>();
        Map<String, String> nodeKeyToFilePath = new HashMap<>();
        List<JavaAstAnalyzer.AstResult> parsedResults = new ArrayList<>();
        Map<JavaAstAnalyzer.AstResult, SourceFileEntity> resultToFile = new HashMap<>();
        List<CodeSymbolEntity> symbolsToSave = new ArrayList<>();

        for (SourceFileEntity file : files) {
            JavaAstAnalyzer.AstResult ast = astAnalyzer.analyze(file.getFilePath(), file.getLanguage(), file.getContent());
            file.setComplexityScore(ast.fileComplexity());
            sourceFileJpaRepository.save(file);

            String base = ast.primaryNode();
            boolean hasDuplicate = filesBySimpleName.getOrDefault(extractSimpleName(file.getFilePath()), List.of()).size() > 1;
            String canonicalNodeKey = hasDuplicate ? disambiguatedNodeKey(file.getFilePath(), base) : base;

            filePathToNodeKey.put(file.getFilePath(), canonicalNodeKey);
            nodeKeyToFilePath.put(canonicalNodeKey, file.getFilePath());
            simpleNameToNodeKeys.computeIfAbsent(base, k -> new ArrayList<>()).add(canonicalNodeKey);

            parsedResults.add(ast);
            resultToFile.put(ast, file);

            for (JavaAstAnalyzer.ParsedSymbol sym : ast.symbols()) {
                symbolsToSave.add(CodeSymbolEntity.create(
                    tenantId, repositoryId, file.getFilePath(),
                    sym.name(), sym.qualifiedName(), sym.kind(),
                    sym.startLine(), sym.endLine(), sym.signature(), sym.complexity()
                ));
            }
        }

        List<CodeDependencyEntity> depsToSave = new ArrayList<>();
        Set<String> dedupEdges = new HashSet<>();

        for (JavaAstAnalyzer.AstResult ast : parsedResults) {
            SourceFileEntity srcFile = resultToFile.get(ast);
            String sourceKey = filePathToNodeKey.get(srcFile.getFilePath());
            String srcDir = parentDirectory(srcFile.getFilePath());

            for (JavaAstAnalyzer.RawDependency raw : ast.dependencies()) {
                List<String> candidates = simpleNameToNodeKeys.get(raw.targetNode());
                if (candidates == null || candidates.isEmpty()) continue;

                // Prefer target in the same package/directory when duplicate class/file names exist
                String targetKey = candidates.size() == 1
                    ? candidates.get(0)
                    : candidates.stream()
                        .filter(k -> parentDirectory(nodeKeyToFilePath.getOrDefault(k, "")).equals(srcDir))
                        .findFirst()
                        .orElse(candidates.get(0));

                if (sourceKey.equals(targetKey)) continue;
                String edgeKey = sourceKey + "->" + targetKey;
                if (!dedupEdges.add(edgeKey)) continue;

                depsToSave.add(CodeDependencyEntity.create(
                    tenantId,
                    repositoryId,
                    sourceKey,
                    srcFile.getFilePath(),
                    targetKey,
                    nodeKeyToFilePath.get(targetKey),
                    raw.dependencyType()
                ));
            }
        }

        codeSymbolRepository.saveAll(symbolsToSave);
        codeDependencyRepository.saveAll(depsToSave);

        repo.setFileCount(files.size());
        repo.setSymbolCount(symbolsToSave.size());
        repo.setDependencyCount(depsToSave.size());
        repo.setIndexStatus("INDEXED");
        repo.setLastIndexedAt(Instant.now());
        repositoryJpaRepository.save(repo);

        sseHubService.publish(tenantId, PipelineEvent.of(
            "REPO_INDEX", repositoryId, "COMPLETED", 100,
            "Indexed " + files.size() + " files, " + symbolsToSave.size() + " symbols, " + depsToSave.size() + " dependencies"
        ));

        auditService.record(tenantId, "ast-worker@forge.dev", "SYSTEM",
            "REPOSITORY_INDEXED", "REPOSITORY", repositoryId,
            "AST indexed " + repo.getFullName() + " (" + symbolsToSave.size() + " symbols, " + depsToSave.size() + " edges)",
            "worker");
    }

    @Transactional(readOnly = true)
    public CodeIntelDtos.DependencyGraphResponse getDependencyGraph(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        RepositoryEntity repo = repositoryService.requireRepository(repositoryId, tenantId);
        List<SourceFileEntity> files = sourceFileJpaRepository.findByRepositoryIdAndTenantIdOrderByFilePathAsc(repositoryId, tenantId);
        List<CodeSymbolEntity> symbols = codeSymbolRepository.findByRepositoryIdAndTenantIdOrderByFilePathAscStartLineAsc(repositoryId, tenantId);
        List<CodeDependencyEntity> deps = codeDependencyRepository.findByRepositoryIdAndTenantId(repositoryId, tenantId);

        Map<String, Integer> symbolCounts = new HashMap<>();
        for (CodeSymbolEntity s : symbols) {
            symbolCounts.merge(s.getFilePath(), 1, Integer::sum);
        }

        Map<String, List<SourceFileEntity>> bySimple = new HashMap<>();
        for (SourceFileEntity f : files) {
            bySimple.computeIfAbsent(extractSimpleName(f.getFilePath()), k -> new ArrayList<>()).add(f);
        }

        Map<String, Integer> incoming = new HashMap<>();
        Map<String, Integer> outgoing = new HashMap<>();
        List<CodeIntelDtos.GraphEdge> edges = new ArrayList<>();
        for (CodeDependencyEntity d : deps) {
            outgoing.merge(d.getSourceNode(), 1, Integer::sum);
            incoming.merge(d.getTargetNode(), 1, Integer::sum);
            edges.add(new CodeIntelDtos.GraphEdge(d.getId(), d.getSourceNode(), d.getTargetNode(), d.getDependencyType()));
        }

        List<CodeIntelDtos.GraphNode> nodes = new ArrayList<>();
        Set<String> seenNodeIds = new HashSet<>();
        for (SourceFileEntity f : files) {
            String simple = extractSimpleName(f.getFilePath());
            String nodeKey = bySimple.getOrDefault(simple, List.of()).size() > 1
                ? disambiguatedNodeKey(f.getFilePath(), simple)
                : simple;
            if (!seenNodeIds.add(nodeKey)) continue;
            nodes.add(new CodeIntelDtos.GraphNode(
                nodeKey,
                nodeKey,
                f.getFilePath(),
                classifyLayer(nodeKey, f.getFilePath()),
                symbolCounts.getOrDefault(f.getFilePath(), 1),
                f.getComplexityScore(),
                incoming.getOrDefault(nodeKey, 0),
                outgoing.getOrDefault(nodeKey, 0)
            ));
        }

        List<List<String>> cycles = detectCircularDependencies(deps);
        return new CodeIntelDtos.DependencyGraphResponse(repo.getId(), repo.getFullName(), nodes, edges, cycles);
    }

    @Transactional(readOnly = true)
    public CodeIntelDtos.ChangeImpactResponse analyzeChangeImpact(String repositoryId, String targetNodeOrFile) {
        String tenantId = TenantContext.requireTenantId();
        return analyzeChangeImpactForTenant(tenantId, repositoryId, targetNodeOrFile);
    }

    @Transactional(readOnly = true)
    public CodeIntelDtos.ChangeImpactResponse analyzeChangeImpactForTenant(
        String tenantId, String repositoryId, String targetNodeOrFile
    ) {
        repositoryService.requireRepository(repositoryId, tenantId);
        List<CodeDependencyEntity> deps = codeDependencyRepository.findByRepositoryIdAndTenantId(repositoryId, tenantId);

        String targetNode = resolveTargetNodeKey(targetNodeOrFile, deps);

        Map<String, List<CodeDependencyEntity>> reverseAdj = new HashMap<>();
        List<String> directDeps = new ArrayList<>();
        for (CodeDependencyEntity d : deps) {
            reverseAdj.computeIfAbsent(d.getTargetNode(), k -> new ArrayList<>()).add(d);
            if (d.getSourceNode().equalsIgnoreCase(targetNode)) {
                directDeps.add(d.getTargetNode());
            }
        }

        Map<String, CodeIntelDtos.ImpactedNode> visited = new LinkedHashMap<>();
        Map<String, List<String>> pathChain = new HashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        Map<String, Integer> depth = new HashMap<>();
        queue.add(targetNode);
        depth.put(targetNode, 0);
        pathChain.put(targetNode, List.of(targetNode));

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentHop = depth.get(current);
            if (currentHop >= 5) continue;
            for (CodeDependencyEntity edge : reverseAdj.getOrDefault(current, List.of())) {
                String dependent = edge.getSourceNode();
                if (!dependent.equalsIgnoreCase(targetNode) && !visited.containsKey(dependent)) {
                    boolean isTest = isTestFileOrNode(dependent, edge.getSourceFile());
                    List<String> chain = new ArrayList<>(pathChain.getOrDefault(current, List.of(current)));
                    chain.add(dependent);
                    pathChain.put(dependent, chain);

                    String reason = currentHop == 0
                        ? dependent + " directly " + edge.getDependencyType().toLowerCase(Locale.ROOT) + " " + targetNode
                        : "Transitive (" + (currentHop + 1) + "-hop) via " + String.join(" -> ", chain);

                    visited.put(dependent, new CodeIntelDtos.ImpactedNode(
                        dependent,
                        edge.getSourceFile(),
                        currentHop + 1,
                        edge.getDependencyType(),
                        isTest,
                        reason
                    ));
                    depth.put(dependent, currentHop + 1);
                    queue.add(dependent);
                }
            }
        }

        List<CodeIntelDtos.ImpactedNode> impacted = new ArrayList<>(visited.values());
        int impactedTests = (int) impacted.stream().filter(CodeIntelDtos.ImpactedNode::isTest).count();
        String risk = impacted.size() >= 4 ? "HIGH" : (impacted.size() >= 2 ? "MEDIUM" : "LOW");

        return new CodeIntelDtos.ChangeImpactResponse(
            repositoryId,
            targetNode,
            impacted.size(),
            impactedTests,
            risk,
            impacted,
            directDeps.stream().distinct().toList()
        );
    }

    /**
     * Detects strongly connected / circular dependency cycles among non-test modules.
     */
    public List<List<String>> detectCircularDependencies(List<CodeDependencyEntity> deps) {
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        for (CodeDependencyEntity d : deps) {
            if ("TESTS".equalsIgnoreCase(d.getDependencyType())) continue;
            adj.computeIfAbsent(d.getSourceNode(), k -> new LinkedHashSet<>()).add(d.getTargetNode());
        }

        List<List<String>> cycles = new ArrayList<>();
        Set<String> canonicalSeen = new HashSet<>();
        Set<String> globalVisited = new HashSet<>();

        for (String start : adj.keySet()) {
            if (globalVisited.contains(start)) continue;
            dfsFindCycles(start, adj, new ArrayList<>(), new HashSet<>(), globalVisited, cycles, canonicalSeen);
        }
        return cycles;
    }

    private void dfsFindCycles(
        String node,
        Map<String, Set<String>> adj,
        List<String> stack,
        Set<String> onStack,
        Set<String> visited,
        List<List<String>> cycles,
        Set<String> canonicalSeen
    ) {
        if (cycles.size() >= 20) return;
        visited.add(node);
        stack.add(node);
        onStack.add(node);

        for (String next : adj.getOrDefault(node, Set.of())) {
            if (onStack.contains(next)) {
                int idx = stack.indexOf(next);
                if (idx >= 0) {
                    List<String> cycle = new ArrayList<>(stack.subList(idx, stack.size()));
                    String sig = canonicalCycleKey(cycle);
                    if (canonicalSeen.add(sig)) {
                        cycle.add(next); // close the loop visually: A -> B -> A
                        cycles.add(cycle);
                    }
                }
            } else if (!visited.contains(next)) {
                dfsFindCycles(next, adj, stack, onStack, visited, cycles, canonicalSeen);
            }
        }

        stack.remove(stack.size() - 1);
        onStack.remove(node);
    }

    private String canonicalCycleKey(List<String> nodes) {
        if (nodes.isEmpty()) return "";
        int minIdx = 0;
        for (int i = 1; i < nodes.size(); i++) {
            if (nodes.get(i).compareTo(nodes.get(minIdx)) < 0) {
                minIdx = i;
            }
        }
        List<String> rotated = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            rotated.add(nodes.get((minIdx + i) % nodes.size()));
        }
        return String.join("->", rotated);
    }

    @Transactional(readOnly = true)
    public CodeIntelDtos.CodeSearchResponse searchCode(String repositoryId, String query) {
        String tenantId = TenantContext.requireTenantId();
        RepositoryEntity repo = repositoryService.requireRepository(repositoryId, tenantId);
        List<SourceFileEntity> files = sourceFileJpaRepository.findByRepositoryIdAndTenantIdOrderByFilePathAsc(repositoryId, tenantId);
        List<CodeSymbolEntity> symbols = codeSymbolRepository.findByRepositoryIdAndTenantIdOrderByFilePathAscStartLineAsc(repositoryId, tenantId);

        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        Set<String> tokens = new LinkedHashSet<>();
        for (String t : normalized.split("[^a-z0-9_]+")) {
            if (t.length() >= 2) tokens.add(t);
        }

        Map<String, SourceFileEntity> fileByPath = new HashMap<>();
        for (SourceFileEntity f : files) fileByPath.put(f.getFilePath(), f);

        List<CodeIntelDtos.SearchHit> hits = new ArrayList<>();

        for (CodeSymbolEntity sym : symbols) {
            double score = 0.0;
            String hay = (sym.getSymbolName() + " " + sym.getQualifiedName() + " " + (sym.getSignature() != null ? sym.getSignature() : ""))
                .toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (sym.getSymbolName().toLowerCase(Locale.ROOT).equals(token)) score += 6.0;
                else if (sym.getSymbolName().toLowerCase(Locale.ROOT).contains(token)) score += 3.5;
                else if (hay.contains(token)) score += 1.5;
            }
            if (score > 0) {
                SourceFileEntity sf = fileByPath.get(sym.getFilePath());
                String snippet = sf != null ? extractSnippet(sf.getContent(), sym.getStartLine()) : sym.getSignature();
                hits.add(new CodeIntelDtos.SearchHit(
                    sf != null ? sf.getId() : "",
                    sym.getFilePath(),
                    sym.getSymbolName(),
                    sym.getSymbolKind(),
                    sym.getStartLine(),
                    Math.min(0.99, Math.round((score / (tokens.size() * 4.5 + 1.0)) * 100.0) / 100.0),
                    snippet
                ));
            }
        }

        for (SourceFileEntity sf : files) {
            String[] lines = sf.getContent().split("\\R");
            for (int i = 0; i < lines.length; i++) {
                String lowerLine = lines[i].toLowerCase(Locale.ROOT);
                int matched = 0;
                for (String token : tokens) {
                    if (lowerLine.contains(token)) matched++;
                }
                if (matched > 0 && (tokens.size() == 1 || matched >= Math.min(2, tokens.size()))) {
                    double score = Math.min(0.94, Math.round(((double) matched / Math.max(1, tokens.size())) * 88.0) / 100.0);
                    hits.add(new CodeIntelDtos.SearchHit(
                        sf.getId(),
                        sf.getFilePath(),
                        extractSimpleName(sf.getFilePath()),
                        "LINE_MATCH",
                        i + 1,
                        score,
                        extractSnippet(sf.getContent(), i + 1)
                    ));
                    break;
                }
            }
        }

        List<CodeIntelDtos.SearchHit> topHits = hits.stream()
            .sorted(Comparator.comparingDouble(CodeIntelDtos.SearchHit::relevanceScore).reversed())
            .limit(12)
            .toList();

        String answer = topHits.isEmpty()
            ? "No matching symbols or code blocks were found in " + repo.getFullName() + " for \"" + query + "\"."
            : "Found " + topHits.size() + " AST & code matches in " + repo.getFullName()
              + ". Highest-relevance anchor: `" + topHits.get(0).symbolName() + "` in `"
              + topHits.get(0).filePath() + ":" + topHits.get(0).lineNumber() + "`.";

        return new CodeIntelDtos.CodeSearchResponse(query, answer, topHits);
    }

    @Transactional(readOnly = true)
    public List<CodeSymbolEntity> listSymbols(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        repositoryService.requireRepository(repositoryId, tenantId);
        return codeSymbolRepository.findByRepositoryIdAndTenantIdOrderByFilePathAscStartLineAsc(repositoryId, tenantId);
    }

    private String resolveTargetNodeKey(String targetNodeOrFile, List<CodeDependencyEntity> deps) {
        String normalized = targetNodeOrFile.replace('\\', '/').trim();
        // 1. Direct match against recorded target/source file path
        for (CodeDependencyEntity d : deps) {
            if (normalized.equalsIgnoreCase(d.getTargetFile())) return d.getTargetNode();
            if (normalized.equalsIgnoreCase(d.getSourceFile())) return d.getSourceNode();
        }
        // 2. Direct match against canonical node name
        for (CodeDependencyEntity d : deps) {
            if (normalized.equalsIgnoreCase(d.getTargetNode())) return d.getTargetNode();
            if (normalized.equalsIgnoreCase(d.getSourceNode())) return d.getSourceNode();
        }
        return extractSimpleName(normalized);
    }

    private boolean isTestFileOrNode(String nodeName, String filePath) {
        String p = filePath == null ? "" : filePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return nodeName.endsWith("Test") || nodeName.endsWith("Tests") || nodeName.endsWith("IT")
            || p.contains("/test/") || p.contains(".test.") || p.contains(".spec.");
    }

    private String disambiguatedNodeKey(String filePath, String simpleName) {
        String dir = parentDirectory(filePath);
        if (dir.isBlank()) return simpleName;
        String parent = dir.contains("/") ? dir.substring(dir.lastIndexOf('/') + 1) : dir;
        return parent + "/" + simpleName;
    }

    private String parentDirectory(String filePath) {
        String norm = filePath.replace('\\', '/');
        int idx = norm.lastIndexOf('/');
        return idx > 0 ? norm.substring(0, idx) : "";
    }

    private String extractSnippet(String content, int line) {
        String[] lines = content.split("\\R");
        int start = Math.max(0, line - 2);
        int end = Math.min(lines.length, line + 3);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return sb.toString().trim();
    }

    public static String extractSimpleName(String pathOrName) {
        String normalized = pathOrName.replace('\\', '/');
        String base = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    public static String classifyLayer(String nodeName, String filePath) {
        String lower = (nodeName + " " + filePath).toLowerCase(Locale.ROOT);
        if (lower.contains("test") || lower.contains(".spec.")) return "TEST";
        if (lower.contains("controller") || lower.contains("gateway") || lower.contains("handler") || lower.contains("/api/")) return "API";
        if (lower.contains("service") || lower.contains("engine") || lower.contains("orchestrator")) return "SERVICE";
        if (lower.contains("repository") || lower.contains("store") || lower.contains("ledger") || lower.contains("dao")) return "DATA";
        if (lower.contains("security") || lower.contains("auth") || lower.contains("filter") || lower.contains("guard") || lower.contains("verifier")) return "SECURITY";
        if (lower.contains("kafka") || lower.contains("outbox") || lower.contains("event") || lower.contains("consumer")) return "MESSAGING";
        return "DOMAIN";
    }
}
