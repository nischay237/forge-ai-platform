package com.forge.platform.codeintel;

import java.util.List;

public final class CodeIntelDtos {

    public record GraphNode(
        String id,
        String label,
        String filePath,
        String layer,
        int symbolCount,
        int complexity,
        int incomingCount,
        int outgoingCount
    ) {}

    public record GraphEdge(
        String id,
        String source,
        String target,
        String dependencyType
    ) {}

    public record DependencyGraphResponse(
        String repositoryId,
        String repositoryName,
        List<GraphNode> nodes,
        List<GraphEdge> edges
    ) {}

    public record ImpactedNode(
        String nodeName,
        String filePath,
        int hopDistance,
        String relationship,
        boolean isTest
    ) {}

    public record ChangeImpactResponse(
        String repositoryId,
        String targetNode,
        int totalImpacted,
        int impactedTests,
        String riskLevel,
        List<ImpactedNode> impactedNodes,
        List<String> directDependencies
    ) {}

    public record SearchHit(
        String fileId,
        String filePath,
        String symbolName,
        String symbolKind,
        int lineNumber,
        double relevanceScore,
        String snippet
    ) {}

    public record CodeSearchResponse(
        String query,
        String synthesizedAnswer,
        List<SearchHit> hits
    ) {}
}
