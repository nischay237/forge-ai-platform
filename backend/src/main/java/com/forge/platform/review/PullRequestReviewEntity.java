package com.forge.platform.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pull_request_reviews")
public class PullRequestReviewEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id", nullable = false)
    private String repositoryId;

    @Column(name = "pr_number", nullable = false)
    private int prNumber;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String author;

    @Column(name = "source_branch", nullable = false)
    private String sourceBranch;

    @Column(name = "target_branch", nullable = false)
    private String targetBranch;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Column(nullable = false)
    private String status;

    @Column(name = "current_stage", nullable = false)
    private String currentStage;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "raw_diff", nullable = false, columnDefinition = "TEXT")
    private String rawDiff;

    @Column(name = "impacted_symbols_json", columnDefinition = "TEXT")
    private String impactedSymbolsJson;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "model_used", nullable = false)
    private String modelUsed;

    @Column(name = "tokens_used", nullable = false)
    private int tokensUsed;

    @Column(name = "estimated_cost_usd", nullable = false, precision = 10, scale = 6)
    private BigDecimal estimatedCostUsd = BigDecimal.ZERO;

    @Column(name = "processing_ms", nullable = false)
    private long processingMs;

    @Column(name = "published_to_github", nullable = false)
    private boolean publishedToGithub;

    @Column(name = "github_comment_url")
    private String githubCommentUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public PullRequestReviewEntity() {}

    public static PullRequestReviewEntity queued(
        String tenantId, String repositoryId, int prNumber, String title,
        String author, String sourceBranch, String targetBranch, String commitSha,
        String rawDiff, String promptVersion
    ) {
        PullRequestReviewEntity r = new PullRequestReviewEntity();
        r.id = UUID.randomUUID().toString();
        r.tenantId = tenantId;
        r.repositoryId = repositoryId;
        r.prNumber = prNumber;
        r.title = title;
        r.author = author;
        r.sourceBranch = sourceBranch;
        r.targetBranch = (targetBranch == null || targetBranch.isBlank()) ? "main" : targetBranch;
        r.commitSha = (commitSha == null || commitSha.isBlank())
            ? UUID.randomUUID().toString().replace("-", "").substring(0, 12) : commitSha;
        r.status = "QUEUED";
        r.currentStage = "QUEUED";
        r.riskScore = 0;
        r.rawDiff = rawDiff;
        r.promptVersion = promptVersion;
        r.modelUsed = "forge-ast-hybrid-v2";
        r.tokensUsed = 0;
        r.estimatedCostUsd = BigDecimal.ZERO;
        r.processingMs = 0;
        r.publishedToGithub = false;
        r.createdAt = Instant.now();
        return r;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public int getPrNumber() { return prNumber; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public String getSourceBranch() { return sourceBranch; }
    public String getTargetBranch() { return targetBranch; }
    public String getCommitSha() { return commitSha; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrentStage() { return currentStage; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public int getRiskScore() { return riskScore; }
    public void setRiskScore(int riskScore) { this.riskScore = riskScore; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getRawDiff() { return rawDiff; }
    public String getImpactedSymbolsJson() { return impactedSymbolsJson; }
    public void setImpactedSymbolsJson(String impactedSymbolsJson) { this.impactedSymbolsJson = impactedSymbolsJson; }
    public String getPromptVersion() { return promptVersion; }
    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
    public int getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; }
    public BigDecimal getEstimatedCostUsd() { return estimatedCostUsd; }
    public void setEstimatedCostUsd(BigDecimal estimatedCostUsd) { this.estimatedCostUsd = estimatedCostUsd; }
    public long getProcessingMs() { return processingMs; }
    public void setProcessingMs(long processingMs) { this.processingMs = processingMs; }
    public boolean isPublishedToGithub() { return publishedToGithub; }
    public void setPublishedToGithub(boolean publishedToGithub) { this.publishedToGithub = publishedToGithub; }
    public String getGithubCommentUrl() { return githubCommentUrl; }
    public void setGithubCommentUrl(String githubCommentUrl) { this.githubCommentUrl = githubCommentUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
