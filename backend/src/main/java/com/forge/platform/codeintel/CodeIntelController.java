package com.forge.platform.codeintel;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}/code-intel")
public class CodeIntelController {

    private final CodeIntelligenceService codeIntelligenceService;
    private final ArchitectureDriftAnalyzer architectureDriftAnalyzer;

    public CodeIntelController(
        CodeIntelligenceService codeIntelligenceService,
        ArchitectureDriftAnalyzer architectureDriftAnalyzer
    ) {
        this.codeIntelligenceService = codeIntelligenceService;
        this.architectureDriftAnalyzer = architectureDriftAnalyzer;
    }

    @GetMapping("/graph")
    public CodeIntelDtos.DependencyGraphResponse dependencyGraph(@PathVariable String repositoryId) {
        return codeIntelligenceService.getDependencyGraph(repositoryId);
    }

    @GetMapping("/symbols")
    public List<CodeSymbolEntity> symbols(@PathVariable String repositoryId) {
        return codeIntelligenceService.listSymbols(repositoryId);
    }

    @GetMapping("/impact")
    public CodeIntelDtos.ChangeImpactResponse impact(
        @PathVariable String repositoryId,
        @RequestParam String target
    ) {
        return codeIntelligenceService.analyzeChangeImpact(repositoryId, target);
    }

    @GetMapping("/drift")
    public CodeIntelDtos.ArchitectureDriftResponse architectureDrift(@PathVariable String repositoryId) {
        return architectureDriftAnalyzer.analyzeRepository(repositoryId);
    }

    @GetMapping("/search")
    public CodeIntelDtos.CodeSearchResponse search(
        @PathVariable String repositoryId,
        @RequestParam String q
    ) {
        return codeIntelligenceService.searchCode(repositoryId, q);
    }
}
