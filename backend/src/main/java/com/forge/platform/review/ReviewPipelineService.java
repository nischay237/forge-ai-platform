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
import com.forge.platform.common.SecretCryptoService;
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
import com.forge.platform.sse.OperationStatusFallbackProvider;
import com.forge.platform.sse.OperationStatusSnapshot;
import com.forge.platform.sse.PipelineEvent;
import com.forge.platform.sse.SseHubService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReviewPipelineService implements DomainEventHandler, OperationStatusFallbackProvider {

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
    private final ApiCompatibilityAnalyzer apiCompatibilityAnalyzer;
    private final MigrationCompatibilityAnalyzer migrationCompatibilityAnalyzer;
    private final TestSelectionService testSelectionService;
    private final ChangeRiskEngine changeRiskEngine;
    private final PolicyService policyService;
    private final AiReviewEngine aiReviewEngine;
    private final SecurityService securityService;
    private final SecretCryptoService cryptoService;
    private final OutboxService outboxService;
    private final IdempotencyService idempotencyService;
    private final RateLimiterService rateLimiterService;
    private final SseHubService sseHubService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
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
        ApiCompatibilityAnalyzer apiCompatibilityAnalyzer,
        MigrationCompatibilityAnalyzer migrationCompatibilityAnalyzer,
        TestSelectionService testSelectionService,
        ChangeRiskEngine changeRiskEngine,
        PolicyService policyService,
        AiReviewEngine aiReviewEngine,
        SecurityService securityService,
        SecretCryptoService cryptoService,
        OutboxService outboxService,
        IdempotencyService idempotencyService,
        RateLimiterService rateLimiterService,
        SseHubService sseHubService,
        AuditService auditService,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
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
        this.apiCompatibilityAnalyzer = apiCompatibilityAnalyzer;
        this.migrationCompatibilityAnalyzer = migrationCompatibilityAnalyzer;
        this.testSelectionService = testSelectionService;
        this.changeRiskEngine = changeRiskEngine;
        this.policyService = policyService;
        this.aiReviewEngine = aiReviewEngine;
        this.securityService = securityService;
        this.cryptoService = cryptoService;
        this.outboxService = outboxService;
        this.idempotencyService = idempotencyService;
        this.rateLimiterService = rateLimiterService;
        this.sseHubService = sseHubService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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

        sseHubService.publish(tenantId, PipelineEvent.stage(
            "PR_REVIEW", review.getId(), "QUEUED",
            "Request accepted & queued", "STARTED", 1, 8,
            "Queued PR #" + review.getPrNumber() + " (" + repo.getFullName() + ") via Transactional Outbox",
            Map.of("prNumber", review.getPrNumber(), "repository", repo.getFullName())
        ));

        return toSummaryDto(review, repo.getFullName(), List.of());
    }

    @Override
    public boolean supports(String topic) {
        return ForgeTopics.PR_REVIEW_REQUESTED.equals(topic);
    }

    @Override
    public void handle(String eventId, String tenantId, String payload) {
        String claimKey = "pr-review:" + eventId;
        if (!idempotencyService.tryClaimEvent(claimKey, tenantId, "PR_REVIEW_REQUESTED")) {
            log.info("Skipping duplicate PR review event {}", eventId);
            return;
        }
        String reviewId = "";
        try {
            JsonNode node = objectMapper.readTree(payload);
            reviewId = node.path("reviewId").asText();
            final String targetReviewId = reviewId;
            transactionTemplate.executeWithoutResult(status -> executeReviewPipeline(tenantId, targetReviewId, true));
            idempotencyService.markCompleted(claimKey);
        } catch (Exception ex) {
            idempotencyService.releaseClaim(claimKey);
            markReviewFailed(tenantId, reviewId, ex.getMessage());
            log.error("Async PR review worker failed for event {}: {}", eventId, ex.getMessage(), ex);
            throw new IllegalStateException("PR review pipeline failed for event " + eventId, ex);
        }
    }

    private void markReviewFailed(String tenantId, String reviewId, String reason) {
        if (reviewId == null || reviewId.isBlank()) return;
        try {
            transactionTemplate.executeWithoutResult(status ->
                reviewRepository.findByIdAndTenantId(reviewId, tenantId).ifPresent(review -> {
                    review.setStatus("FAILED");
                    review.setCurrentStage("FAILED");
                    review.setSummary("Review pipeline failed: " + reason);
                    reviewRepository.save(review);
                })
            );
            sseHubService.publish(tenantId, PipelineEvent.stage(
                "PR_REVIEW", reviewId, "FAILED",
                "Review pipeline failed", "FAILED", 8, 8,
                "Review pipeline encountered an error: " + (reason != null ? reason : "unexpected error"),
                Map.of("error", reason != null ? reason : "unexpected error")
            ));
        } catch (Exception ignored) {
            // best effort status update
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
            sseHubService.publish(tenantId, PipelineEvent.stage(
                "PR_REVIEW", reviewId, "INGESTING_DIFF",
                "Parsing unified diff hunks", "IN_PROGRESS", 2, 8,
                "Parsing unified diff hunks & changed symbols for PR #" + review.getPrNumber(),
                Map.of("prNumber", review.getPrNumber())
            ));
        }

        List<UnifiedDiffParser.ParsedDiffFile> parsedFiles = diffParser.parse(review.getRawDiff());

        // Stage 3: AST Dependency Change Impact Analysis
        review.setCurrentStage("AST_IMPACT_ANALYSIS");
        reviewRepository.save(review);
        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.stage(
                "PR_REVIEW", reviewId, "AST_IMPACT_ANALYSIS",
                "Computing AST dependency blast radius", "IN_PROGRESS", 3, 8,
                "Computing JavaParser & TS/JS AST dependency blast radius across " + parsedFiles.size() + " changed file(s)",
                Map.of("changedFiles", parsedFiles.size())
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
        int totalImpactedDependents = impacts.stream().mapToInt(CodeIntelDtos.ChangeImpactResponse::totalImpacted).sum();

        // Stage 4: Deterministic API & Database Migration Compatibility Analysis
        review.setCurrentStage("CONTRACT_AND_MIGRATION_ANALYSIS");
        reviewRepository.save(review);
        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.stage(
                "PR_REVIEW", reviewId, "CONTRACT_AND_MIGRATION_ANALYSIS",
                "Checking REST API & SQL migration compatibility", "IN_PROGRESS", 4, 8,
                "Inspecting OpenAPI/Spring REST endpoints & SQL DDL safety (" + totalImpactedDependents + " downstream dependents)",
                Map.of("changedFiles", parsedFiles.size(), "downstreamDependents", totalImpactedDependents)
            ));
        }
        List<AiReviewEngine.FindingProposal> apiFindings = apiCompatibilityAnalyzer.analyze(parsedFiles);
        List<AiReviewEngine.FindingProposal> migrationFindings = migrationCompatibilityAnalyzer.analyze(parsedFiles);

        // Stage 5: Security Scanner + Static Defect Engine + Optional AI Contextual Enrichment
        review.setCurrentStage("SECURITY_SCANNING");
        reviewRepository.save(review);
        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.stage(
                "PR_REVIEW", reviewId, "SECURITY_SCANNING",
                "Scanning secrets, SAST & prompt-injection patterns", "IN_PROGRESS", 5, 8,
                "Running secret entropy/pattern scanner, SAST rules & prompt-injection guard",
                Map.of("apiFindings", apiFindings.size(), "migrationFindings", migrationFindings.size())
            ));
        }

        review.setCurrentStage("AI_CONTEXT_REVIEW");
        reviewRepository.save(review);
        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.stage(
                "PR_REVIEW", reviewId, "AI_CONTEXT_REVIEW",
                "Synthesizing contextual review findings", "IN_PROGRESS", 6, 8,
                "Synthesizing contextual explanations & patch suggestions (" + review.getPromptVersion() + ")",
                Map.of("promptVersion", review.getPromptVersion())
            ));
        }

        String tenantAiProvider = tenantRepository.findById(tenantId)
            .map(Tenant::getAiProvider)
            .orElse("HYBRID");

        AiReviewEngine.ReviewOutput aiOutput = aiReviewEngine.analyzeDiff(
            review.getTitle(), parsedFiles, impacts, review.getPromptVersion(), tenantAiProvider
        );

        // Merge deterministic API + Migration findings with Security/Static/AI findings cleanly
        List<AiReviewEngine.FindingProposal> allProposals = mergeAllProposals(
            aiOutput.findings(), apiFindings, migrationFindings
        );

        // Stage 7: Intelligent Test Selection (static, never claimed executed) + Deterministic Change Risk Engine
        review.setCurrentStage("RISK_AND_TEST_SELECTION");
        reviewRepository.save(review);
        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.stage(
                "PR_REVIEW", reviewId, "RISK_AND_TEST_SELECTION",
                "Evaluating policy merge decision & impacted tests", "IN_PROGRESS", 7, 8,
                "Evaluating Policy-as-Code merge decision (PASS/WARN/BLOCK) & selecting impacted tests across " + allProposals.size() + " finding(s)",
                Map.of("candidateFindings", allProposals.size(), "modelUsed", aiOutput.modelUsed())
            ));
        }

        TestSelectionService.TestSelectionPlan testPlan = testSelectionService.selectTests(
            tenantId, review.getRepositoryId(), parsedFiles, impacts
        );
        ChangeRiskPolicyEntity policy = policyService.resolveEffectivePolicy(tenantId, review.getRepositoryId());
        ChangeRiskEngine.ChangeRiskAssessment riskAssessment = changeRiskEngine.evaluate(
            parsedFiles, allProposals, impacts, testPlan, policy
        );

        // Persist structured review findings with stable content-anchored fingerprints
        List<ReviewFindingEntity> savedFindings = new ArrayList<>();
        for (var proposal : allProposals) {
            ReviewFindingEntity entity = ReviewFindingEntity.create(
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
            );
            entity.setFingerprint(computeFindingFingerprint(proposal.filePath(), proposal.ruleId(), proposal.evidence()));
            savedFindings.add(findingRepository.save(entity));
        }

        // Persist security findings into Security Center
        if (!aiOutput.securityIssues().isEmpty()) {
            securityService.recordFindingsForReview(tenantId, review.getRepositoryId(), review.getId(), aiOutput.securityIssues());
        }

        long elapsedMs = Math.max(45L, (System.nanoTime() - startNanos) / 1_000_000L);
        reviewLatencyTimer.record(Duration.ofMillis(elapsedMs));
        reviewsCompletedCounter.increment();

        review.setImpactedSymbolsJson(writeJsonSafe(impacts, "[]"));
        review.setRiskBreakdownJson(writeJsonSafe(riskAssessment, "{}"));
        review.setRecommendedTestsJson(writeJsonSafe(testPlan, "{}"));
        review.setStatus("COMPLETED");
        review.setCurrentStage("COMPLETED");
        review.setRiskScore(riskAssessment.riskScore());
        review.setMergeDecision(riskAssessment.mergeDecision());
        review.setSummary(riskAssessment.decisionSummary());
        review.setModelUsed(aiOutput.modelUsed());
        review.setTokensUsed(aiOutput.tokensUsed());
        review.setEstimatedCostUsd(aiOutput.estimatedCostUsd());
        review.setProcessingMs(elapsedMs);
        review.setCompletedAt(Instant.now());
        reviewRepository.save(review);

        // Enqueue non-blocking async predictive risk evaluation via Transactional Outbox
        outboxService.enqueue(
            tenantId,
            "PR_PREDICTION",
            review.getId(),
            "PR_PREDICTION_REQUESTED",
            ForgeTopics.PR_PREDICTION_REQUESTED,
            Map.of(
                "eventId", "pr-pred-" + review.getId() + "-" + review.getCommitSha(),
                "tenantId", tenantId,
                "reviewId", review.getId()
            )
        );

        auditService.record(tenantId, "review-worker@forge.dev", "SYSTEM",
            "PR_REVIEW_COMPLETED", "PR_REVIEW", review.getId(),
            "Completed PR #" + review.getPrNumber() + " review: decision=" + riskAssessment.mergeDecision()
                + ", findings=" + savedFindings.size()
                + ", risk=" + riskAssessment.riskScore() + "/100, selectedTests=" + testPlan.recommendedCount()
                + ", latency=" + elapsedMs + "ms",
            "worker");

        if (emitLiveProgress) {
            sseHubService.publish(tenantId, PipelineEvent.stage(
                "PR_REVIEW", reviewId, "COMPLETED",
                "Review complete & persisted", "COMPLETED", 8, 8,
                "Review complete [" + riskAssessment.mergeDecision() + "]: " + savedFindings.size()
                    + " finding(s) (Risk " + riskAssessment.riskScore() + "/100, " + elapsedMs + "ms)",
                Map.of(
                    "mergeDecision", riskAssessment.mergeDecision(),
                    "riskScore", riskAssessment.riskScore(),
                    "findingsCount", savedFindings.size(),
                    "selectedTests", testPlan.recommendedCount(),
                    "elapsedMs", elapsedMs
                )
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
                f.changeType(),
                RepositoryService.detectLanguage(f.filePath()),
                f.originalContent(),
                f.modifiedContent(),
                f.addedLines().size(),
                f.removedLines().size(),
                f.changedSymbols()
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

        ChangeRiskEngine.ChangeRiskAssessment riskAssessment = null;
        if (review.getRiskBreakdownJson() != null && !review.getRiskBreakdownJson().isBlank()) {
            try {
                riskAssessment = objectMapper.readValue(review.getRiskBreakdownJson(), ChangeRiskEngine.ChangeRiskAssessment.class);
            } catch (Exception ignored) {
                riskAssessment = null;
            }
        }

        TestSelectionService.TestSelectionPlan testPlan = null;
        if (review.getRecommendedTestsJson() != null && !review.getRecommendedTestsJson().isBlank()) {
            try {
                testPlan = objectMapper.readValue(review.getRecommendedTestsJson(), TestSelectionService.TestSelectionPlan.class);
            } catch (Exception ignored) {
                testPlan = null;
            }
        }

        return new ReviewDtos.ReviewDetailDto(
            toSummaryDto(review, repoName, findings),
            files,
            findings,
            impacts,
            riskAssessment,
            testPlan
        );
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
            review.setPublishedToGithub(true);
            review.setPublishStatus("PUBLISHED_TO_GITHUB");
        } else {
            review.setPublishedToGithub(true);
            review.setPublishStatus("APPROVED_LOCALLY");
        }
        reviewRepository.save(review);

        String auditMsg = liveCommentUrl != null && !liveCommentUrl.isBlank()
            ? "Posted " + approvedCount + " human-approved review comments LIVE to GitHub PR #"
                + review.getPrNumber() + " (" + liveCommentUrl + ")"
            : "Approved " + approvedCount + " review comments locally for PR #"
                + review.getPrNumber() + " (add a workspace GitHub Token in Settings to post live to GitHub)";

        auditService.recordCurrent("PR_REVIEW_PUBLISHED_TO_GITHUB", "PR_REVIEW", review.getId(), auditMsg);

        return toSummaryDto(review, repoName, findings);
    }

    private List<AiReviewEngine.FindingProposal> mergeAllProposals(
        List<AiReviewEngine.FindingProposal> base,
        List<AiReviewEngine.FindingProposal> apiFindings,
        List<AiReviewEngine.FindingProposal> migrationFindings
    ) {
        List<AiReviewEngine.FindingProposal> merged = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (List<AiReviewEngine.FindingProposal> list : List.of(apiFindings, migrationFindings, base)) {
            for (var f : list) {
                String key = f.filePath() + ":" + f.lineNumber() + ":" + f.ruleId();
                if (keys.add(key)) {
                    merged.add(f);
                }
            }
        }
        return merged;
    }

    private String computeFindingFingerprint(String filePath, String ruleId, String evidence) {
        String normalized = (evidence == null ? "" : evidence).replaceAll("\\s+", " ").trim();
        return cryptoService.sha256Hex(filePath + "|" + ruleId + "|" + normalized);
    }

    private String writeJsonSafe(Object obj, String fallback) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String formatGithubReviewMarkdown(
        PullRequestReviewEntity review,
        List<ReviewFindingEntity> approvedFindings,
        String reviewerEmail
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("## FORGE Change Impact & Risk Review — PR #").append(review.getPrNumber()).append("\n\n");
        sb.append("**Merge Recommendation:** `").append(review.getMergeDecision()).append("`  |  ")
          .append("**Risk Score:** `").append(review.getRiskScore()).append("/100`  |  ")
          .append("**Approved Findings:** `").append(approvedFindings.size()).append("`  |  ")
          .append("**Reviewed By:** `").append(reviewerEmail).append("`\n\n");

        if (approvedFindings.isEmpty()) {
            sb.append("No configured blocking security, contract, migration, or static issues were flagged.\n");
            sb.append("> *Note: PASS indicates no configured blocking rules triggered; it is not a guarantee of production safety.*\n");
        } else {
            int idx = 1;
            for (ReviewFindingEntity f : approvedFindings) {
                sb.append("### ").append(idx++).append(". [").append(f.getSeverity()).append("] ")
                  .append(f.getTitle()).append("\n");
                sb.append("- **Location:** `").append(f.getFilePath()).append(":").append(f.getLineNumber())
                  .append("` (`").append(f.getRuleId()).append("`)\n");
                sb.append("- **Why it matters:** ").append(f.getExplanation()).append("\n");
                if (f.getEvidence() != null && !f.getEvidence().isBlank()) {
                    sb.append("- **Evidence:** `").append(f.getEvidence().replace("`", "'")).append("`\n");
                }
                if (f.getSuggestedPatch() != null && !f.getSuggestedPatch().isBlank()) {
                    sb.append("\n**Suggested fix:**\n```\n")
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
            r.getMergeDecision(),
            r.getSummary(),
            r.getPromptVersion(),
            r.getModelUsed(),
            r.getTokensUsed(),
            r.getEstimatedCostUsd(),
            r.getProcessingMs(),
            r.isPublishedToGithub(),
            r.getPublishStatus(),
            r.getGithubCommentUrl(),
            findings.size(),
            critical,
            high,
            r.getCreatedAt(),
            r.getCompletedAt()
        );
    }

    @Override
    public List<String> supportedEventTypes() {
        return List.of("PR_REVIEW");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OperationStatusSnapshot> findFallbackSnapshot(String tenantId, String eventType, String resourceId) {
        if (!"PR_REVIEW".equals(eventType) || resourceId == null || resourceId.isBlank()) {
            return Optional.empty();
        }
        return reviewRepository.findByIdAndTenantId(resourceId, tenantId).map(review -> {
            String rawStatus = review.getStatus() != null ? review.getStatus().toUpperCase() : "QUEUED";
            String snapshotStatus;
            String stage;
            String label;
            int completedStages;
            String eventStatus;
            if ("COMPLETED".equals(rawStatus)) {
                snapshotStatus = "COMPLETED";
                stage = "COMPLETED";
                label = "Review complete & persisted";
                completedStages = 8;
                eventStatus = "COMPLETED";
            } else if ("FAILED".equals(rawStatus)) {
                snapshotStatus = "FAILED";
                stage = "FAILED";
                label = "Review pipeline failed";
                completedStages = 0;
                eventStatus = "FAILED";
            } else {
                snapshotStatus = "RUNNING";
                stage = (review.getCurrentStage() != null && !review.getCurrentStage().isBlank())
                    ? review.getCurrentStage() : "QUEUED";
                label = "In progress (" + stage + ")";
                completedStages = "QUEUED".equals(stage) ? 0 : 1;
                eventStatus = "QUEUED".equals(stage) ? "STARTED" : "IN_PROGRESS";
            }

            Instant startedAt = review.getCreatedAt();
            Instant updatedAt = review.getCompletedAt() != null ? review.getCompletedAt() : startedAt;
            Map<String, Object> details = new HashMap<>();
            details.put("recoveredFromDatabase", true);
            details.put("prNumber", review.getPrNumber());
            details.put("riskScore", review.getRiskScore());
            details.put("mergeDecision", review.getMergeDecision());

            PipelineEvent recoveredEvent = PipelineEvent.stage(
                "PR_REVIEW",
                review.getId(),
                stage,
                label,
                eventStatus,
                Math.max(1, completedStages),
                8,
                "Recovered persisted review state (" + rawStatus + ") for PR #" + review.getPrNumber(),
                details
            ).withSequence(1L);

            return new OperationStatusSnapshot(
                PipelineEvent.buildOperationId("PR_REVIEW", review.getId()),
                "PR_REVIEW",
                review.getId(),
                recoveredEvent.correlationId(),
                snapshotStatus,
                stage,
                label,
                completedStages,
                8,
                1L,
                startedAt,
                updatedAt,
                review.getCompletedAt(),
                List.of(recoveredEvent)
            );
        });
    }
}
