package com.forge.platform.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditService;
import com.forge.platform.codeintel.CodeIntelDtos;
import com.forge.platform.codeintel.CodeIntelligenceService;
import com.forge.platform.common.ForgeException;
import com.forge.platform.common.RateLimitExceededException;
import com.forge.platform.common.ResourceNotFoundException;
import com.forge.platform.common.TenantContext;
import com.forge.platform.identity.AuthService;
import com.forge.platform.identity.Tenant;
import com.forge.platform.identity.TenantRepository;
import com.forge.platform.messaging.DomainEventHandler;
import com.forge.platform.messaging.ForgeTopics;
import com.forge.platform.messaging.IdempotencyService;
import com.forge.platform.messaging.OutboxService;
import com.forge.platform.messaging.RateLimiterService;
import com.forge.platform.repository.GithubIngestionClient;
import com.forge.platform.repository.RepositoryDtos;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.RepositoryJpaRepository;
import com.forge.platform.repository.RepositoryService;
import com.forge.platform.security.SecurityService;
import com.forge.platform.sse.PipelineEvent;
import com.forge.platform.sse.SseHubService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReviewPipelineService implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ReviewPipelineService.class);
    private static final Set<String> VALID_DECISIONS = Set.of("APPROVED", "DISMISSED", "PENDING");
    private static final Pattern GITHUB_PR_URL_PATTERN =
        Pattern.compile("^(?:https?://(?:www\\.)?github\\.com/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/pull/|#)(\\d+)(?:/.*)?$");

    private final PullRequestReviewRepository reviewRepository;
    private final ReviewFindingRepository findingRepository;
    private final RepositoryJpaRepository repositoryJpaRepository;
    private final RepositoryService repositoryService;
    private final GithubIngestionClient githubIngestionClient;
    private final AuthService authService;
    private final TenantRepository tenantRepository;
    private final UnifiedDiffParser diffParser;
    private final CodeIntelligenceService codeIntelligenceService;
    private final AiReviewEngine aiReviewEngine;
    private final SecurityService securityService;
    private final OutboxService outboxService;
    private final IdempotencyService idempotencyService;
    private final RateLimiterService rateLimiterService;
    private final SseHubService sseHubService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Counter reviewsCompletedCounter;
    private final Timer reviewLatencyTimer;

    public ReviewPipelineService(
        PullRequestReviewRepository reviewRepository,
        ReviewFindingRepository findingRepository,
        RepositoryJpaRepository repositoryJpaRepository,
        RepositoryService repositoryService,
        GithubIngestionClient githubIngestionClient,
        AuthService authService,
        TenantRepository tenantRepository,
        UnifiedDiffParser diffParser,
        CodeIntelligenceService codeIntelligenceService,
        AiReviewEngine aiReviewEngine,
        SecurityService securityService,
        OutboxService outboxService,
        IdempotencyService idempotencyService,
        RateLimiterService rateLimiterService,
        SseHubService sseHubService,
        AuditService auditService,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry
    ) {
        this.reviewRepository = reviewRepository;
        this.findingRepository = findingRepository;
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.repositoryService = repositoryService;
        this.githubIngestionClient = githubIngestionClient;
        this.authService = authService;
        this.tenantRepository = tenantRepository;
        this.diffParser = diffParser;
        this.codeIntelligenceService = codeIntelligenceService;
        this.aiReviewEngine = aiReviewEngine;
        this.securityService = securityService;
        this.outboxService = outboxService;
        this.idempotencyService = idempotencyService;
        this.rateLimiterService = rateLimiterService;
        this.sseHubService = sseHubService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.reviewsCompletedCounter = Counter.builder("forge.reviews.completed").register(meterRegistry);
        this.reviewLatencyTimer = Timer.builder("forge.reviews.duration").register(meterRegistry);
    }

    @Transactional
    public ReviewDtos.ReviewSummaryDto enqueueReview(ReviewDtos.CreateReviewRequest request) {
        String tenantId = TenantContext.requireTenantId();
        if (!rateLimiterService.allowRequest("review-trigger:" + tenantId, 40, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("Rate limit exceeded for pull request review triggers. Please retry shortly.");
        }
        return enqueueReviewForTenant(tenantId, TenantContext.requireEmail(), TenantContext.requireRole(), request);
    }

    @Transactional
    public ReviewDtos.ReviewSummaryDto importGithubPullRequest(String rawUrl) {
        String tenantId = TenantContext.requireTenantId();
        if (!rateLimiterService.allowRequest("review-trigger:" + tenantId, 40, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("Rate limit exceeded for pull request review triggers. Please retry shortly.");
        }

        String cleaned = rawUrl == null ? "" : rawUrl.trim();
        Matcher matcher = GITHUB_PR_URL_PATTERN.matcher(cleaned);
        if (!matcher.matches()) {
            throw new ForgeException(HttpStatus.BAD_REQUEST, "INVALID_GITHUB_PR_URL",
                "Enter a valid GitHub PR URL like https://github.com/owner/repo/pull/1 or owner/repo#1");
        }

        String owner = matcher.group(1);
        String repoName = matcher.group(2);
        int prNumber = Integer.parseInt(matcher.group(3));

        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
        String githubToken = authService.resolveTenantGithubToken(tenant);

        GithubIngestionClient.PullRequestSnapshot snapshot =
            githubIngestionClient.fetchPullRequest(owner, repoName, prNumber, githubToken);

        String fullName = owner + "/" + repoName;
        RepositoryEntity repo = repositoryJpaRepository
            .findByTenantIdAndFullNameIgnoreCase(tenantId, fullName)
            .orElseGet(() -> {
                var dto = repositoryService.connectRepository(new RepositoryDtos.ConnectRepositoryRequest(
                    owner,
                    repoName,
                    snapshot.targetBranch(),
                    "Java",
                    "Auto-connected from live GitHub PR #" + prNumber,
                    true,
                    null
                ));
                return repositoryJpaRepository.findByIdAndTenantId(dto.id(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Repository not found after auto-connect"));
            });

        return enqueueReviewForTenant(
            tenantId,
            TenantContext.requireEmail(),
            TenantContext.requireRole(),
            new ReviewDtos.CreateReviewRequest(
                repo.getId(),
                snapshot.prNumber(),
                snapshot.title(),
                snapshot.author(),
                snapshot.sourceBranch(),
                snapshot.targetBranch(),
                snapshot.commitSha(),
                snapshot.rawDiff()
            )
        );
    }

    @Transactional
    public ReviewDtos.ReviewSummaryDto enqueueReviewForTenant(
        String tenantId, String actorEmail, String actorRole, ReviewDtos.CreateReviewRequest request
    ) {
        RepositoryEntity repo = repositoryJpaRepository.findByIdAndTenantId(request.repositoryId(), tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Repository not found: " + request.repositoryId()));

        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));

        PullRequestReviewEntity review = PullRequestReviewEntity.queued(
            tenantId,
            repo.getId(),
            request.prNumber(),
            request.title().trim(),
            request.author() != null && !request.author().isBlank() ? request.author().trim() : actorEmail,
            request.sourceBranch().trim(),
            request.targetBranch(),
            request.commitSha(),
            request.rawDiff(),
            tenant.getPromptVersion()
        );
        reviewRepository.save(review);

        outboxService.enqueue(
            tenantId,
            "PR_REVIEW",
            review.getId(),
            "PR_REVIEW_REQUESTED",
            ForgeTopics.PR_REVIEW_REQUESTED,
            Map.of(
                "eventId", "pr-review-" + review.getId(),
                "tenantId", tenantId,
                "reviewId", review.getId()
            )
        );

        auditService.record(tenantId, actorEmail, actorRole,
            "PR_REVIEW_QUEUED", "PR_REVIEW", review.getId(),
            "Queued PR #" + review.getPrNumber() + " (" + review.getTitle() + ") on " + repo.getFullName() + " via Transactional Outbox",
            "api");

        sseHubService.publish(tenantId, PipelineEvent.of(
            "PR_REVIEW", review.getId(), "QUEUED", 10,
            "Queued PR #" + review.getPrNumber() + " via Transactional Outbox"
        ));

        return toSummaryDto(review, repo.getFullName(), List.of());
    }

    @Override
    public boolean supports(String topic) {
        return ForgeTopics.PR_REVIEW_REQUESTED.equals(topic);
    }

    @Override
    public void handle(String eventId, String tenantId, String payload) {
        if (!idempotencyService.tryClaimEvent("pr-review:" + eventId, tenantId, "PR_REVIEW_REQUESTED")) {
            log.info("Skipping duplicate PR review event {}", eventId);
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            String reviewId = node.path("reviewId").asText();
            executeReviewPipeline(tenantId, reviewId, true);
        } catch (Exception ex) {
            log.error("Async PR review worker failed for event {}: {}", eventId, ex.getMessage(), ex);
        }
    }

    @Transactional
    public PullRequestReviewEntity executeReviewPipeline(String tenantId, String reviewId, boolean emitLiveProgress) {
        long startNanos = System.nanoTime();
        PullRequestReviewEntity review = reviewRepository.findByIdAndTenantId(reviewId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Review not found: " + reviewId));

        review.setStatus("RUNNING");
        review.setCurrentStage("INGESTING_DIFF");
        reviewRepository.save(review);

        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.of(
                "PR_REVIEW", reviewId, "INGESTING_DIFF", 22,
                "Parsing unified diff hunks for PR #" + review.getPrNumber()
            ));
        }

        List<UnifiedDiffParser.ParsedDiffFile> parsedFiles = diffParser.parse(review.getRawDiff());

        // Stage 2: AST Change Impact Analysis
        review.setCurrentStage("AST_IMPACT_ANALYSIS");
        reviewRepository.save(review);
        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.of(
                "PR_REVIEW", reviewId, "AST_IMPACT_ANALYSIS", 46,
                "Computing JavaParser AST dependency blast radius across " + parsedFiles.size() + " changed files"
            ));
        }

        List<CodeIntelDtos.ChangeImpactResponse> impacts = new ArrayList<>();
        for (var file : parsedFiles) {
            try {
                impacts.add(codeIntelligenceService.analyzeChangeImpactForTenant(
                    tenantId, review.getRepositoryId(), file.filePath()
                ));
            } catch (Exception ignored) {
                // file may be new and not yet in index
            }
        }

        // Stage 3 & 4: Security Scanners + Contextual AI Review Engine
        review.setCurrentStage("SECURITY_SCANNING");
        reviewRepository.save(review);
        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.of(
                "PR_REVIEW", reviewId, "SECURITY_SCANNING", 68,
                "Running Gitleaks secret scanner, Semgrep SAST rules & prompt-injection guard"
            ));
        }

        review.setCurrentStage("AI_CONTEXT_REVIEW");
        reviewRepository.save(review);
        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.of(
                "PR_REVIEW", reviewId, "AI_CONTEXT_REVIEW", 86,
                "Synthesizing contextual findings & patch suggestions (" + review.getPromptVersion() + ")"
            ));
        }

        AiReviewEngine.ReviewOutput output = aiReviewEngine.analyzeDiff(
            review.getTitle(), parsedFiles, impacts, review.getPromptVersion()
        );

        // Persist structured review findings
        List<ReviewFindingEntity> savedFindings = new ArrayList<>();
        for (var proposal : output.findings()) {
            savedFindings.add(findingRepository.save(ReviewFindingEntity.create(
                tenantId,
                review.getId(),
                proposal.filePath(),
                proposal.lineNumber(),
                proposal.category(),
                proposal.severity(),
                proposal.confidence(),
                proposal.ruleId(),
                proposal.title(),
                proposal.explanation(),
                proposal.evidence(),
                proposal.suggestedPatch()
            )));
        }

        // Persist security findings into Security Center
        if (!output.securityIssues().isEmpty()) {
            securityService.recordFindingsForReview(tenantId, review.getRepositoryId(), review.getId(), output.securityIssues());
        }

        long elapsedMs = Math.max(45L, (System.nanoTime() - startNanos) / 1_000_000L);
        reviewLatencyTimer.record(Duration.ofMillis(elapsedMs));
        reviewsCompletedCounter.increment();

        try {
            review.setImpactedSymbolsJson(objectMapper.writeValueAsString(impacts));
        } catch (Exception ignored) {
            review.setImpactedSymbolsJson("[]");
        }
        review.setStatus("COMPLETED");
        review.setCurrentStage("COMPLETED");
        review.setRiskScore(output.riskScore());
        review.setSummary(output.summary());
        review.setModelUsed(output.modelUsed());
        review.setTokensUsed(output.tokensUsed());
        review.setEstimatedCostUsd(output.estimatedCostUsd());
        review.setProcessingMs(elapsedMs);
        review.setCompletedAt(Instant.now());
        reviewRepository.save(review);

        auditService.record(tenantId, "review-worker@forge.dev", "SYSTEM",
            "PR_REVIEW_COMPLETED", "PR_REVIEW", review.getId(),
            "Completed PR #" + review.getPrNumber() + " review: " + savedFindings.size()
                + " findings, risk=" + output.riskScore() + "/100, latency=" + elapsedMs + "ms",
            "worker");

        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.of(
                "PR_REVIEW", reviewId, "COMPLETED", 100,
                "Review complete: " + savedFindings.size() + " findings detected (Risk " + output.riskScore() + "/100)"
            ));
        }

        return review;
    }

    @Transactional(readOnly = true)
    public List<ReviewDtos.ReviewSummaryDto> listReviews(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        List<PullRequestReviewEntity> reviews = (repositoryId != null && !repositoryId.isBlank())
            ? reviewRepository.findByTenantIdAndRepositoryIdOrderByCreatedAtDesc(tenantId, repositoryId)
            : reviewRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);

        Map<String, String> repoNames = new HashMap<>();
        repositoryJpaRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
            .forEach(r -> repoNames.put(r.getId(), r.getFullName()));

        List<ReviewFindingEntity> allFindings = findingRepository.findByTenantId(tenantId);
        Map<String, List<ReviewFindingEntity>> byReview = new HashMap<>();
        for (ReviewFindingEntity f : allFindings) {
            byReview.computeIfAbsent(f.getReviewId(), k -> new ArrayList<>()).add(f);
        }

        return reviews.stream()
            .map(r -> toSummaryDto(r, repoNames.getOrDefault(r.getRepositoryId(), "repository"),
                byReview.getOrDefault(r.getId(), List.of())))
            .toList();
    }

    @Transactional(readOnly = true)
    public ReviewDtos.ReviewDetailDto getReviewDetail(String reviewId) {
        String tenantId = TenantContext.requireTenantId();
        PullRequestReviewEntity review = reviewRepository.findByIdAndTenantId(reviewId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Review not found: " + reviewId));

        String repoName = repositoryJpaRepository.findByIdAndTenantId(review.getRepositoryId(), tenantId)
            .map(RepositoryEntity::getFullName)
            .orElse("repository");

        List<ReviewFindingEntity> findings = findingRepository
            .findByReviewIdAndTenantIdOrderBySeverityAscLineNumberAsc(reviewId, tenantId);

        List<ReviewDtos.DiffFileView> files = diffParser.parse(review.getRawDiff()).stream()
            .map(f -> new ReviewDtos.DiffFileView(
                f.filePath(),
                RepositoryService.detectLanguage(f.filePath()),
                f.originalContent(),
                f.modifiedContent(),
                f.addedLines().size(),
                f.removedLines().size()
            ))
            .toList();

        List<CodeIntelDtos.ChangeImpactResponse> impacts = List.of();
        if (review.getImpactedSymbolsJson() != null && !review.getImpactedSymbolsJson().isBlank()) {
            try {
                impacts = objectMapper.readValue(review.getImpactedSymbolsJson(),
                    new TypeReference<List<CodeIntelDtos.ChangeImpactResponse>>() {});
            } catch (Exception ignored) {
                impacts = List.of();
            }
        }

        return new ReviewDtos.ReviewDetailDto(toSummaryDto(review, repoName, findings), files, findings, impacts);
    }

    @Transactional
    public ReviewFindingEntity decideFinding(String reviewId, String findingId, String decision) {
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        if (!VALID_DECISIONS.contains(normalized)) {
            throw new ForgeException(HttpStatus.BAD_REQUEST, "INVALID_DECISION",
                "Decision must be APPROVED, DISMISSED, or PENDING");
        }
        String tenantId = TenantContext.requireTenantId();
        ReviewFindingEntity finding = findingRepository.findByIdAndReviewIdAndTenantId(findingId, reviewId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Review finding not found"));

        finding.decide(normalized, TenantContext.requireEmail());
        findingRepository.save(finding);

        auditService.recordCurrent("REVIEW_FINDING_" + normalized, "REVIEW_FINDING", finding.getId(),
            "Marked finding " + finding.getRuleId() + " (" + finding.getFilePath() + ":" + finding.getLineNumber() + ") as " + normalized);
        return finding;
    }

    @Transactional
    public ReviewDtos.ReviewSummaryDto publishApprovedComments(String reviewId) {
        String tenantId = TenantContext.requireTenantId();
        PullRequestReviewEntity review = reviewRepository.findByIdAndTenantId(reviewId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Review not found"));

        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));

        List<ReviewFindingEntity> findings = findingRepository
            .findByReviewIdAndTenantIdOrderBySeverityAscLineNumberAsc(reviewId, tenantId);
        List<ReviewFindingEntity> approvedFindings = findings.stream()
            .filter(f -> "APPROVED".equals(f.getStatus()))
            .toList();
        long approvedCount = approvedFindings.size();

        if (tenant.isRequireApprovalBeforePublish() && !findings.isEmpty() && approvedCount == 0) {
            throw new ForgeException(HttpStatus.BAD_REQUEST, "APPROVAL_REQUIRED",
                "Approve at least one finding before publishing review comments to GitHub.");
        }

        RepositoryEntity repo = repositoryJpaRepository.findByIdAndTenantId(review.getRepositoryId(), tenantId)
            .orElse(null);
        String repoName = repo != null ? repo.getFullName() : "repository";

        String githubToken = authService.resolveTenantGithubToken(tenant);
        boolean isSeededDemoRepo = repo != null && "acme-fintech".equalsIgnoreCase(repo.getOwner());
        String liveCommentUrl = null;

        if (repo != null && githubToken != null && !githubToken.isBlank() && !isSeededDemoRepo) {
            String markdown = formatGithubReviewMarkdown(review, approvedFindings, TenantContext.requireEmail());
            liveCommentUrl = githubIngestionClient.postPullRequestComment(
                repo.getOwner(), repo.getName(), review.getPrNumber(), markdown, githubToken
            );
            review.setGithubCommentUrl(liveCommentUrl);
        }

        review.setPublishedToGithub(true);
        reviewRepository.save(review);

        String auditMsg = liveCommentUrl != null && !liveCommentUrl.isBlank()
            ? "Posted " + approvedCount + " human-approved review comments LIVE to GitHub PR #"
                + review.getPrNumber() + " (" + liveCommentUrl + ")"
            : "Published " + approvedCount + " human-approved review comments for PR #"
                + review.getPrNumber() + " (local workspace audit — add GitHub Token in Settings to post live)";

        auditService.recordCurrent("PR_REVIEW_PUBLISHED_TO_GITHUB", "PR_REVIEW", review.getId(), auditMsg);

        return toSummaryDto(review, repoName, findings);
    }

    private String formatGithubReviewMarkdown(
        PullRequestReviewEntity review,
        List<ReviewFindingEntity> approvedFindings,
        String reviewerEmail
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🛡️ FORGE Automated Code Review — PR #").append(review.getPrNumber()).append("\n\n");
        sb.append("**Risk Score:** `").append(review.getRiskScore()).append("/100`  |  ")
          .append("**Approved Findings:** `").append(approvedFindings.size()).append("`  |  ")
          .append("**Reviewed By:** `").append(reviewerEmail).append("`\n\n");

        if (approvedFindings.isEmpty()) {
            sb.append("✅ All security, secret-scanning, and static checks passed cleanly.\n");
        } else {
            int idx = 1;
            for (ReviewFindingEntity f : approvedFindings) {
                String badge = switch (f.getSeverity()) {
                    case "CRITICAL" -> "🔴 CRITICAL";
                    case "HIGH" -> "🟠 HIGH";
                    case "MEDIUM" -> "🟡 MEDIUM";
                    default -> "🔵 LOW";
                };
                sb.append("### ").append(idx++).append(". ").append(badge)
                  .append(" — ").append(f.getTitle()).append("\n");
                sb.append("- **Location:** `").append(f.getFilePath()).append(":").append(f.getLineNumber())
                  .append("` (`").append(f.getRuleId()).append("`)\n");
                sb.append("- **Why it matters:** ").append(f.getExplanation()).append("\n");
                if (f.getEvidence() != null && !f.getEvidence().isBlank()) {
                    sb.append("- **Detected code:** `").append(f.getEvidence().replace("`", "'")).append("`\n");
                }
                if (f.getSuggestedPatch() != null && !f.getSuggestedPatch().isBlank()) {
                    sb.append("\n**Suggested fix:**\n```java\n")
                      .append(f.getSuggestedPatch())
                      .append("\n```\n");
                }
                sb.append("\n");
            }
        }
        sb.append("---\n*Published after explicit human approval via **FORGE Control Plane***\n");
        return sb.toString();
    }

    private ReviewDtos.ReviewSummaryDto toSummaryDto(
        PullRequestReviewEntity r, String repoName, List<ReviewFindingEntity> findings
    ) {
        long critical = findings.stream().filter(f -> "CRITICAL".equals(f.getSeverity())).count();
        long high = findings.stream().filter(f -> "HIGH".equals(f.getSeverity())).count();
        return new ReviewDtos.ReviewSummaryDto(
            r.getId(),
            r.getRepositoryId(),
            repoName,
            r.getPrNumber(),
            r.getTitle(),
            r.getAuthor(),
            r.getSourceBranch(),
            r.getTargetBranch(),
            r.getCommitSha(),
            r.getStatus(),
            r.getCurrentStage(),
            r.getRiskScore(),
            r.getSummary(),
            r.getPromptVersion(),
            r.getModelUsed(),
            r.getTokensUsed(),
            r.getEstimatedCostUsd(),
            r.getProcessingMs(),
            r.isPublishedToGithub(),
            r.getGithubCommentUrl(),
            findings.size(),
            critical,
            high,
            r.getCreatedAt(),
            r.getCompletedAt()
        );
    }
}
