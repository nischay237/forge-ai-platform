package com.forge.platform.analytics;

import com.forge.platform.ci.CiBuildEntity;
import com.forge.platform.ci.CiBuildRepository;
import com.forge.platform.common.TenantContext;
import com.forge.platform.messaging.ForgeCacheService;
import com.forge.platform.messaging.OutboxService;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.RepositoryJpaRepository;
import com.forge.platform.review.EvaluationRunEntity;
import com.forge.platform.review.EvaluationRunRepository;
import com.forge.platform.review.PullRequestReviewEntity;
import com.forge.platform.review.PullRequestReviewRepository;
import com.forge.platform.review.ReviewFindingEntity;
import com.forge.platform.review.ReviewFindingRepository;
import com.forge.platform.security.SecurityFindingEntity;
import com.forge.platform.security.SecurityFindingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalyticsService {

    public record SeverityPoint(String severity, long reviewFindings, long securityFindings) {}
    public record CategoryPoint(String category, long count) {}
    public record ReviewLatencyPoint(String prLabel, long processingMs, int riskScore, int tokensUsed) {}
    public record RepoHealthPoint(String repository, int files, int symbols, int dependencies, long reviews, long openSecurity) {}

    public record OverviewAnalyticsDto(
        long connectedRepositories,
        long totalPrReviews,
        long totalFindings,
        long openSecurityIssues,
        long totalCiBuilds,
        double ciSuccessRatePct,
        long avgReviewLatencyMs,
        long totalTokensUsed,
        BigDecimal totalEstimatedCostUsd,
        Double latestEvalF1,
        Double latestEvalPrecision,
        Double latestEvalRecall,
        List<SeverityPoint> findingsBySeverity,
        List<CategoryPoint> findingsByCategory,
        List<ReviewLatencyPoint> reviewLatencySeries,
        List<RepoHealthPoint> repositoryHealth,
        Map<String, Object> infrastructureTelemetry
    ) {}

    private final RepositoryJpaRepository repositoryJpaRepository;
    private final PullRequestReviewRepository reviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final SecurityFindingRepository securityFindingRepository;
    private final CiBuildRepository ciBuildRepository;
    private final EvaluationRunRepository evaluationRunRepository;
    private final OutboxService outboxService;
    private final ForgeCacheService cacheService;

    public AnalyticsService(
        RepositoryJpaRepository repositoryJpaRepository,
        PullRequestReviewRepository reviewRepository,
        ReviewFindingRepository reviewFindingRepository,
        SecurityFindingRepository securityFindingRepository,
        CiBuildRepository ciBuildRepository,
        EvaluationRunRepository evaluationRunRepository,
        OutboxService outboxService,
        ForgeCacheService cacheService
    ) {
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.reviewRepository = reviewRepository;
        this.reviewFindingRepository = reviewFindingRepository;
        this.securityFindingRepository = securityFindingRepository;
        this.ciBuildRepository = ciBuildRepository;
        this.evaluationRunRepository = evaluationRunRepository;
        this.outboxService = outboxService;
        this.cacheService = cacheService;
    }

    @Transactional(readOnly = true)
    public OverviewAnalyticsDto getOverview() {
        String tenantId = TenantContext.requireTenantId();

        List<RepositoryEntity> repos = repositoryJpaRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        List<PullRequestReviewEntity> reviews = reviewRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        List<ReviewFindingEntity> reviewFindings = reviewFindingRepository.findByTenantId(tenantId);
        List<SecurityFindingEntity> securityFindings = securityFindingRepository.findByTenantIdOrderByDetectedAtDesc(tenantId);
        List<CiBuildEntity> builds = ciBuildRepository.findByTenantIdOrderByTriggeredAtDesc(tenantId);
        List<EvaluationRunEntity> evals = evaluationRunRepository.findByTenantIdOrderByExecutedAtDesc(tenantId);

        long openSec = securityFindings.stream().filter(s -> "OPEN".equals(s.getStatus())).count();
        long passedBuilds = builds.stream().filter(b -> "PASSED".equals(b.getStatus())).count();
        double ciSuccessPct = builds.isEmpty() ? 100.0
            : Math.round(((double) passedBuilds / builds.size()) * 1000.0) / 10.0;

        long avgLatency = reviews.isEmpty() ? 0L
            : Math.round(reviews.stream().mapToLong(PullRequestReviewEntity::getProcessingMs).average().orElse(0));

        long totalTokens = reviews.stream().mapToLong(PullRequestReviewEntity::getTokensUsed).sum();
        BigDecimal totalCost = reviews.stream()
            .map(PullRequestReviewEntity::getEstimatedCostUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Double latestF1 = evals.isEmpty() ? null : evals.get(0).getF1Score().doubleValue();
        Double latestPrec = evals.isEmpty() ? null : evals.get(0).getPrecisionScore().doubleValue();
        Double latestRec = evals.isEmpty() ? null : evals.get(0).getRecallScore().doubleValue();

        List<SeverityPoint> bySeverity = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW").stream()
            .map(sev -> new SeverityPoint(
                sev,
                reviewFindings.stream().filter(f -> sev.equals(f.getSeverity())).count(),
                securityFindings.stream().filter(s -> sev.equals(s.getSeverity())).count()
            ))
            .toList();

        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        for (ReviewFindingEntity f : reviewFindings) {
            categoryCounts.merge(f.getCategory(), 1L, Long::sum);
        }
        List<CategoryPoint> byCategory = categoryCounts.entrySet().stream()
            .map(e -> new CategoryPoint(e.getKey(), e.getValue()))
            .toList();

        List<ReviewLatencyPoint> latencySeries = new ArrayList<>();
        List<PullRequestReviewEntity> chronological = new ArrayList<>(reviews);
        java.util.Collections.reverse(chronological);
        for (PullRequestReviewEntity r : chronological) {
            latencySeries.add(new ReviewLatencyPoint(
                "PR #" + r.getPrNumber(),
                r.getProcessingMs(),
                r.getRiskScore(),
                r.getTokensUsed()
            ));
        }

        List<RepoHealthPoint> repoHealth = repos.stream()
            .map(repo -> new RepoHealthPoint(
                repo.getFullName(),
                repo.getFileCount(),
                repo.getSymbolCount(),
                repo.getDependencyCount(),
                reviews.stream().filter(r -> r.getRepositoryId().equals(repo.getId())).count(),
                securityFindings.stream().filter(s -> s.getRepositoryId().equals(repo.getId()) && "OPEN".equals(s.getStatus())).count()
            ))
            .toList();

        Map<String, Object> infra = new LinkedHashMap<>();
        infra.put("cacheBackend", cacheService.backendName());
        infra.putAll(outboxService.statsForTenant(tenantId));

        return new OverviewAnalyticsDto(
            repos.size(),
            reviews.size(),
            reviewFindings.size(),
            openSec,
            builds.size(),
            ciSuccessPct,
            avgLatency,
            totalTokens,
            totalCost,
            latestF1,
            latestPrec,
            latestRec,
            bySeverity,
            byCategory,
            latencySeries,
            repoHealth,
            infra
        );
    }
}
