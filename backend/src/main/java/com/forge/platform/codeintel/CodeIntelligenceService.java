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
import org.springframework.transaction.annotation.Transactional;

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
        ObjectMapper objectMapper
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
    }

    @Override
    public boolean supports(String topic) {
        return ForgeTopics.REPO_INDEX_REQUESTED.equals(topic);
    }

    @Override
    public void handle(String eventId, String tenantId, String payload) {
        if (!idempotencyService.tryClaimEvent("repo-index:" + eventId, tenantId, "REPO_INDEX_REQUESTED")) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            String repoId = node.path("repositoryId").asText();
            indexRepository(tenantId, repoId);
        } catch (Exception ex) {
            log.error("Async repository indexing failed for event {}: {}", eventId, ex.getMessage(), ex);
        }
    }

    @Transactional
    public void indexRepository(String tenantId, String repositoryId) {
        RepositoryEntity repo = repositoryJpaRepository.findByIdAndTenantId(repositoryId, tenantId).orElse(null);
        if (repo == null) return;

        sseHubService.publish(tenantId, PipelineEvent.of(
            "REPO_INDEX", repositoryId, "PARSING_AST", 30,
            "Parsing JavaParser AST for " + repo.getFullName()
        ));

        List<SourceFileEntity> files = sourceFileJpaRepository
            .findByRepositoryIdAndTenantIdOrderByFilePathAsc(repositoryId, tenantId);

        codeSymbolRepository.deleteByRepositoryIdAndTenantId(repositoryId, tenantId);
        codeDependencyRepository.deleteByRepositoryIdAndTenantId(repositoryId, tenantId);

        Map<String, String> nodeToFile = new HashMap<>();
        List<JavaAstAnalyzer.AstResult> parsedResults = new ArrayList<>();
        Map<JavaAstAnalyzer.AstResult, SourceFileEntity> resultToFile = new HashMap<>();

        List<CodeSymbolEntity> symbolsToSave = new ArrayList<>();

        for (SourceFileEntity file : files) {
            JavaAstAnalyzer.AstResult ast = astAnalyzer.analyze(file.getFilePath(), file.getLanguage(), file.getContent());
            file.setComplexityScore(ast.fileComplexity());
            sourceFileJpaRepository.save(file);

            nodeToFile.put(ast.primaryNode(), file.getFilePath());
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
            for (JavaAstAnalyzer.RawDependency raw : ast.dependencies()) {
                // Only keep edges targeting nodes inside this repository so the graph stays crisp & accurate
                if (!nodeToFile.containsKey(raw.targetNode())) continue;
                String edgeKey = raw.sourceNode() + "->" + raw.targetNode();
                if (!dedupEdges.add(edgeKey)) continue;
                depsToSave.add(CodeDependencyEntity.create(
                    tenantId,
                    repositoryId,
                    raw.sourceNode(),
                    srcFile.getFilePath(),
                    raw.targetNode(),
                    nodeToFile.get(raw.targetNode()),
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
            String nodeName = extractSimpleName(f.getFilePath());
            if (!seenNodeIds.add(nodeName)) continue;
            nodes.add(new CodeIntelDtos.GraphNode(
                nodeName,
                nodeName,
                f.getFilePath(),
                classifyLayer(nodeName, f.getFilePath()),
                symbolCounts.getOrDefault(f.getFilePath(), 1),
                f.getComplexityScore(),
                incoming.getOrDefault(nodeName, 0),
                outgoing.getOrDefault(nodeName, 0)
            ));
        }

        return new CodeIntelDtos.DependencyGraphResponse(repo.getId(), repo.getFullName(), nodes, edges);
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
        String targetNode = extractSimpleName(targetNodeOrFile);
        List<CodeDependencyEntity> deps = codeDependencyRepository.findByRepositoryIdAndTenantId(repositoryId, tenantId);

        // Reverse adjacency: target -> list of dependents that call/import/test target
        Map<String, List<CodeDependencyEntity>> reverseAdj = new HashMap<>();
        List<String> directDeps = new ArrayList<>();
        for (CodeDependencyEntity d : deps) {
            reverseAdj.computeIfAbsent(d.getTargetNode(), k -> new ArrayList<>()).add(d);
            if (d.getSourceNode().equalsIgnoreCase(targetNode)) {
                directDeps.add(d.getTargetNode());
            }
        }

        // BFS to compute blast radius
        Map<String, CodeIntelDtos.ImpactedNode> visited = new LinkedHashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        Map<String, Integer> depth = new HashMap<>();
        queue.add(targetNode);
        depth.put(targetNode, 0);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentHop = depth.get(current);
            if (currentHop >= 4) continue;
            for (CodeDependencyEntity edge : reverseAdj.getOrDefault(current, List.of())) {
                String dependent = edge.getSourceNode();
                if (!dependent.equalsIgnoreCase(targetNode) && !visited.containsKey(dependent)) {
                    boolean isTest = dependent.endsWith("Test") || edge.getSourceFile().contains("/test/");
                    visited.put(dependent, new CodeIntelDtos.ImpactedNode(
                        dependent,
                        edge.getSourceFile(),
                        currentHop + 1,
                        edge.getDependencyType(),
                        isTest
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

        // Also match file contents for semantic/keyword phrases not in symbol names
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

    private String extractSimpleName(String pathOrName) {
        String normalized = pathOrName.replace('\\', '/');
        String base = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private String classifyLayer(String nodeName, String filePath) {
        String lower = (nodeName + " " + filePath).toLowerCase(Locale.ROOT);
        if (lower.contains("test")) return "TEST";
        if (lower.contains("controller") || lower.contains("gateway") || lower.contains("api")) return "API";
        if (lower.contains("service") || lower.contains("engine") || lower.contains("orchestrator")) return "SERVICE";
        if (lower.contains("repository") || lower.contains("store") || lower.contains("ledger") || lower.contains("dao")) return "DATA";
        if (lower.contains("security") || lower.contains("auth") || lower.contains("filter") || lower.contains("guard")) return "SECURITY";
        if (lower.contains("kafka") || lower.contains("outbox") || lower.contains("event") || lower.contains("consumer")) return "MESSAGING";
        return "DOMAIN";
    }
}
