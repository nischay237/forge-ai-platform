package com.forge.platform.review;

import com.forge.platform.codeintel.CodeIntelDtos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ReviewDtos {

    public record CreateReviewRequest(
        @NotBlank String repositoryId,
        @NotNull Integer prNumber,
        @NotBlank String title,
        String author,
        @NotBlank String sourceBranch,
        String targetBranch,
        String commitSha,
        @NotBlank String rawDiff
    ) {}

    public record ImportGithubPrRequest(@NotBlank String prUrl) {}

    public record FindingDecisionRequest(@NotBlank String decision) {}

    public record ReviewSummaryDto(
        String id,
        String repositoryId,
        String repositoryName,
        int prNumber,
        String title,
        String author,
        String sourceBranch,
        String targetBranch,
        String commitSha,
        String status,
        String currentStage,
        int riskScore,
        String summary,
        String promptVersion,
        String modelUsed,
        int tokensUsed,
        BigDecimal estimatedCostUsd,
        long processingMs,
        boolean publishedToGithub,
        String githubCommentUrl,
        long findingCount,
        long criticalCount,
        long highCount,
        Instant createdAt,
        Instant completedAt
    ) {}

    public record DiffFileView(
        String filePath,
        String language,
        String originalContent,
        String modifiedContent,
        int addedCount,
        int removedCount
    ) {}

    public record ReviewDetailDto(
        ReviewSummaryDto review,
        List<DiffFileView> files,
        List<ReviewFindingEntity> findings,
        List<CodeIntelDtos.ChangeImpactResponse> changeImpacts
    ) {}

    public record EvaluationCaseResult(
        String caseId,
        String name,
        String expectedRulePrefix,
        boolean shouldDetectIssue,
        boolean detected,
        List<String> triggeredRules,
        String outcome,
        long latencyMs
    ) {}

    public record EvaluationRunDto(
        String id,
        String promptVersion,
        String modelUsed,
        int datasetSize,
        int truePositives,
        int falsePositives,
        int falseNegatives,
        double precision,
        double recall,
        double f1Score,
        double falsePositiveRate,
        long avgLatencyMs,
        List<EvaluationCaseResult> caseResults,
        Instant executedAt
    ) {}
}
