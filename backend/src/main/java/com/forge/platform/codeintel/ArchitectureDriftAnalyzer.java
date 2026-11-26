package com.forge.platform.codeintel;

import com.forge.platform.common.TenantContext;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.RepositoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic Architecture Drift & Layer Boundary Analyzer.
 * Checks indexed code_dependencies against layered architecture boundary rules
 * and circular dependency invariants.
 */
@Service
public class ArchitectureDriftAnalyzer {

    public record LayerRule(
        String ruleId,
        String sourceLayer,
        String forbiddenTargetLayer,
        String severity,
        String description
    ) {}

    private static final List<LayerRule> DEFAULT_RULES = List.of(
        new LayerRule("ARCH-001", "API", "DATA", "HIGH",
            "API/Controller layer should not bypass the Service layer to depend directly on DATA repositories"),
        new LayerRule("ARCH-002", "DATA", "API", "CRITICAL",
            "DATA persistence layer must never depend on API/Controller layer"),
        new LayerRule("ARCH-003", "DOMAIN", "API", "HIGH",
            "DOMAIN layer must remain decoupled from API transport controllers"),
        new LayerRule("ARCH-004", "DATA", "SERVICE", "HIGH",
            "DATA layer must not call upward into SERVICE orchestration layer"),
        new LayerRule("ARCH-005", "MESSAGING", "API", "MEDIUM",
            "MESSAGING transport handlers should route through SERVICE instead of calling API controllers")
    );

    private final RepositoryService repositoryService;
    private final CodeDependencyRepository codeDependencyRepository;
    private final CodeIntelligenceService codeIntelligenceService;

    public ArchitectureDriftAnalyzer(
        RepositoryService repositoryService,
        CodeDependencyRepository codeDependencyRepository,
        CodeIntelligenceService codeIntelligenceService
    ) {
        this.repositoryService = repositoryService;
        this.codeDependencyRepository = codeDependencyRepository;
        this.codeIntelligenceService = codeIntelligenceService;
    }

    @Transactional(readOnly = true)
    public CodeIntelDtos.ArchitectureDriftResponse analyzeRepository(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        RepositoryEntity repo = repositoryService.requireRepository(repositoryId, tenantId);
        List<CodeDependencyEntity> deps = codeDependencyRepository.findByRepositoryIdAndTenantId(repositoryId, tenantId);
        return evaluateEdges(repo.getId(), repo.getFullName(), deps, DEFAULT_RULES);
    }

    public CodeIntelDtos.ArchitectureDriftResponse evaluateEdges(
        String repositoryId,
        String repositoryName,
        List<CodeDependencyEntity> deps,
        List<LayerRule> rules
    ) {
        List<LayerRule> activeRules = (rules != null && !rules.isEmpty()) ? rules : DEFAULT_RULES;
        List<CodeIntelDtos.ArchitectureDriftViolation> violations = new ArrayList<>();

        for (CodeDependencyEntity dep : deps) {
            if ("TESTS".equalsIgnoreCase(dep.getDependencyType())) {
                continue;
            }
            String srcLayer = CodeIntelligenceService.classifyLayer(dep.getSourceNode(), dep.getSourceFile());
            String tgtLayer = CodeIntelligenceService.classifyLayer(dep.getTargetNode(),
                dep.getTargetFile() != null ? dep.getTargetFile() : dep.getTargetNode());

            for (LayerRule rule : activeRules) {
                if (rule.sourceLayer().equalsIgnoreCase(srcLayer)
                    && rule.forbiddenTargetLayer().equalsIgnoreCase(tgtLayer)) {
                    violations.add(new CodeIntelDtos.ArchitectureDriftViolation(
                        rule.ruleId(),
                        rule.severity(),
                        dep.getSourceNode(),
                        dep.getSourceFile(),
                        srcLayer,
                        dep.getTargetNode(),
                        dep.getTargetFile(),
                        tgtLayer,
                        dep.getDependencyType(),
                        rule.description() + " (" + dep.getSourceNode() + " -> " + dep.getTargetNode() + ")"
                    ));
                }
            }
        }

        List<List<String>> cycles = codeIntelligenceService.detectCircularDependencies(deps);
        return new CodeIntelDtos.ArchitectureDriftResponse(
            repositoryId,
            repositoryName,
            deps.size(),
            violations.size(),
            cycles.size(),
            violations,
            cycles
        );
    }
}
