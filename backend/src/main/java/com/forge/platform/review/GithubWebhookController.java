package com.forge.platform.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditService;
import com.forge.platform.common.ForgeException;
import com.forge.platform.common.RateLimitExceededException;
import com.forge.platform.common.ResourceNotFoundException;
import com.forge.platform.common.SecretCryptoService;
import com.forge.platform.identity.AuthService;
import com.forge.platform.identity.Tenant;
import com.forge.platform.identity.TenantRepository;
import com.forge.platform.messaging.IdempotencyService;
import com.forge.platform.messaging.RateLimiterService;
import com.forge.platform.repository.GithubIngestionClient;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.RepositoryJpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/github")
public class GithubWebhookController {

    private final TenantRepository tenantRepository;
    private final RepositoryJpaRepository repositoryJpaRepository;
    private final AuthService authService;
    private final SecretCryptoService cryptoService;
    private final IdempotencyService idempotencyService;
    private final RateLimiterService rateLimiterService;
    private final ReviewPipelineService reviewPipelineService;
    private final GithubIngestionClient githubIngestionClient;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public GithubWebhookController(
        TenantRepository tenantRepository,
        RepositoryJpaRepository repositoryJpaRepository,
        AuthService authService,
        SecretCryptoService cryptoService,
        IdempotencyService idempotencyService,
        RateLimiterService rateLimiterService,
        ReviewPipelineService reviewPipelineService,
        GithubIngestionClient githubIngestionClient,
        AuditService auditService,
        ObjectMapper objectMapper
    ) {
        this.tenantRepository = tenantRepository;
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.authService = authService;
        this.cryptoService = cryptoService;
        this.idempotencyService = idempotencyService;
        this.rateLimiterService = rateLimiterService;
        this.reviewPipelineService = reviewPipelineService;
        this.githubIngestionClient = githubIngestionClient;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{tenantSlug}")
    public ResponseEntity<Map<String, Object>> receivePullRequestWebhook(
        @PathVariable String tenantSlug,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
        @RequestHeader(value = "X-GitHub-Event", defaultValue = "pull_request") String eventName,
        @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
        @RequestBody String rawBody
    ) throws Exception {
        if (!rateLimiterService.allowRequest("webhook:" + tenantSlug, 60, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("Webhook rate limit exceeded");
        }

        Tenant tenant = tenantRepository.findBySlug(tenantSlug)
            .orElseThrow(() -> new ResourceNotFoundException("Workspace not found for slug: " + tenantSlug));

        String secret = authService.resolveTenantWebhookSecret(tenant);
        if (!cryptoService.verifyGithubSignature(rawBody, secret, signature)) {
            auditService.record(tenant.getId(), "github-webhook@external", "WEBHOOK",
                "WEBHOOK_SIGNATURE_REJECTED", "WEBHOOK", deliveryId,
                "Rejected GitHub webhook with invalid HMAC-SHA256 signature", "webhook");
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE",
                "HMAC-SHA256 signature verification failed");
        }

        String dedupKey = "gh-delivery:" + tenant.getId() + ":" +
            (deliveryId != null && !deliveryId.isBlank() ? deliveryId : cryptoService.sha256Hex(rawBody));
        if (!idempotencyService.tryClaimEvent(dedupKey, tenant.getId(), "GITHUB_WEBHOOK")) {
            return ResponseEntity.ok(Map.of("status", "DUPLICATE_IGNORED", "deliveryId", dedupKey));
        }

        if (!"pull_request".equalsIgnoreCase(eventName)) {
            return ResponseEntity.ok(Map.of("status", "IGNORED_EVENT", "event", eventName));
        }

        JsonNode root = objectMapper.readTree(rawBody);
        String action = root.path("action").asText("opened");
        if (!action.isBlank() && !action.equalsIgnoreCase("opened")
            && !action.equalsIgnoreCase("synchronize") && !action.equalsIgnoreCase("reopened")) {
            return ResponseEntity.ok(Map.of("status", "IGNORED_ACTION", "action", action));
        }

        String repoFullName = root.path("repository").path("full_name").asText("");
        RepositoryEntity repo = repositoryJpaRepository
            .findByTenantIdAndFullNameIgnoreCase(tenant.getId(), repoFullName)
            .orElseThrow(() -> new ResourceNotFoundException("Repository not connected: " + repoFullName));

        JsonNode pr = root.path("pull_request");
        int prNumber = pr.path("number").asInt(1);
        String title = pr.path("title").asText("GitHub PR #" + prNumber);
        String author = pr.path("user").path("login").asText("github-user");
        String headRef = pr.path("head").path("ref").asText("feature/pr-" + prNumber);
        String baseRef = pr.path("base").path("ref").asText(repo.getDefaultBranch());
        String sha = pr.path("head").path("sha").asText("webhook-sha");
        String diff = root.path("diff").asText(pr.path("diff").asText(""));
        if (diff.isBlank()) {
            try {
                String ghToken = authService.resolveTenantGithubToken(tenant);
                var snapshot = githubIngestionClient.fetchPullRequest(repo.getOwner(), repo.getName(), prNumber, ghToken);
                diff = snapshot.rawDiff();
            } catch (Exception ex) {
                diff = "diff --git a/README.md b/README.md\n+++ b/README.md\n@@ -1,1 +1,2 @@\n+// Webhook PR #" + prNumber;
            }
        }

        ReviewDtos.ReviewSummaryDto review = reviewPipelineService.enqueueReviewForTenant(
            tenant.getId(),
            author + "@github",
            "WEBHOOK",
            new ReviewDtos.CreateReviewRequest(repo.getId(), prNumber, title, author, headRef, baseRef, sha, diff)
        );

        return ResponseEntity.accepted().body(Map.of(
            "status", "ACCEPTED",
            "reviewId", review.id(),
            "repository", repo.getFullName(),
            "prNumber", prNumber
        ));
    }
}
