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
import com.forge.platform.messaging.EngineeringWebhookListener;
import com.forge.platform.messaging.IdempotencyService;
import com.forge.platform.messaging.RateLimiterService;
import com.forge.platform.repository.GithubIngestionClient;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.RepositoryJpaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/github")
public class GithubWebhookController {

    private static final String DUMMY_HMAC_SECRET = "forge-constant-time-unknown-tenant-hmac-dummy-key";
    static final int MAX_WEBHOOK_PAYLOAD_BYTES = 1_048_576; // 1 MiB

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
    private final ObjectProvider<EngineeringWebhookListener> webhookListeners;
    private final MeterRegistry meterRegistry;
    private final boolean allowInlineDiff;

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
        ObjectMapper objectMapper,
        ObjectProvider<EngineeringWebhookListener> webhookListeners,
        MeterRegistry meterRegistry,
        @Value("${forge.webhooks.allow-inline-diff:false}") boolean allowInlineDiff
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
        this.webhookListeners = webhookListeners;
        this.meterRegistry = meterRegistry;
        this.allowInlineDiff = allowInlineDiff;
    }

    @PostMapping("/{tenantSlug}")
    public ResponseEntity<Map<String, Object>> receivePullRequestWebhook(
        @PathVariable String tenantSlug,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
        @RequestHeader(value = "X-GitHub-Event", defaultValue = "pull_request") String eventName,
        @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
        @RequestBody String rawBody,
        HttpServletRequest httpRequest
    ) throws Exception {
        // 0. Guard against oversized webhook payloads before HMAC/JSON parsing
        if (rawBody != null && rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_WEBHOOK_PAYLOAD_BYTES) {
            recordWebhookMetric(eventName, "payload_too_large");
            throw new ForgeException(HttpStatus.PAYLOAD_TOO_LARGE, "WEBHOOK_PAYLOAD_TOO_LARGE",
                "Webhook payload exceeds maximum allowed size of 1 MiB");
        }

        // 1. Per-source-IP flood guard BEFORE tenant lookup so unauthenticated callers cannot DoS a victim tenant's bucket
        String clientIp = resolveClientIp(httpRequest);
        if (!rateLimiterService.allowRequest("webhook-ip:" + clientIp, 120, Duration.ofMinutes(1))) {
            recordWebhookMetric(eventName, "rate_limited");
            throw new RateLimitExceededException("Webhook rate limit exceeded");
        }

        // 2. Lookup tenant and verify HMAC-SHA256 with uniform 401 response (prevents tenant slug enumeration)
        Tenant tenant = tenantRepository.findBySlug(tenantSlug).orElse(null);
        if (tenant == null) {
            cryptoService.verifyGithubSignature(rawBody, DUMMY_HMAC_SECRET, signature);
            recordWebhookMetric(eventName, "rejected_signature");
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE",
                "HMAC-SHA256 signature verification failed");
        }

        String secret = authService.resolveTenantWebhookSecret(tenant);
        if (!cryptoService.verifyGithubSignature(rawBody, secret, signature)) {
            auditService.recordIsolated(tenant.getId(), "github-webhook@external", "WEBHOOK",
                "WEBHOOK_SIGNATURE_REJECTED", "WEBHOOK", deliveryId,
                "Rejected GitHub webhook with invalid HMAC-SHA256 signature", clientIp);
            recordWebhookMetric(eventName, "rejected_signature");
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE",
                "HMAC-SHA256 signature verification failed");
        }

        // 3. Authenticated per-tenant rate limit
        if (!rateLimiterService.allowRequest("webhook-tenant:" + tenant.getId(), 60, Duration.ofMinutes(1))) {
            recordWebhookMetric(eventName, "rate_limited");
            throw new RateLimitExceededException("Workspace webhook rate limit exceeded");
        }

        if (!"pull_request".equalsIgnoreCase(eventName)) {
            EngineeringWebhookListener listener = webhookListeners.orderedStream()
                .filter(l -> l.supportsGithubEvent(eventName))
                .findFirst()
                .orElse(null);
            if (listener == null) {
                recordWebhookMetric(eventName, "ignored");
                return ResponseEntity.ok(Map.of("status", "IGNORED_EVENT", "event", eventName));
            }
            String nonPrPayloadHash = cryptoService.sha256Hex(rawBody);
            String nonPrKey = (deliveryId != null && !deliveryId.isBlank())
                ? "gh-eng:" + tenant.getId() + ":" + eventName + ":" + deliveryId.trim()
                : "gh-eng:" + tenant.getId() + ":" + eventName + ":" + nonPrPayloadHash;
            if (!idempotencyService.tryClaimEvent(nonPrKey, tenant.getId(), "GITHUB_" + eventName.toUpperCase())) {
                recordWebhookMetric(eventName, "duplicate");
                return ResponseEntity.ok(Map.of("status", "DUPLICATE_IGNORED", "deliveryId", nonPrKey));
            }
            try {
                Map<String, Object> result = listener.onGithubEvent(tenant.getId(), eventName, nonPrKey, rawBody);
                idempotencyService.markCompleted(nonPrKey);
                recordWebhookMetric(eventName, "accepted");
                return ResponseEntity.accepted().body(result);
            } catch (Exception ex) {
                idempotencyService.releaseClaim(nonPrKey);
                recordWebhookMetric(eventName, "failed");
                throw ex;
            }
        }

        JsonNode root = objectMapper.readTree(rawBody);
        String action = root.path("action").asText("opened");
        if (!action.isBlank() && !action.equalsIgnoreCase("opened")
            && !action.equalsIgnoreCase("synchronize") && !action.equalsIgnoreCase("reopened")) {
            recordWebhookMetric(eventName, "ignored");
            return ResponseEntity.ok(Map.of("status", "IGNORED_ACTION", "action", action));
        }

        // 4. Replay protection combining deliveryId (when supplied) AND body hash
        String payloadHash = cryptoService.sha256Hex(rawBody);
        String deliveryKey = (deliveryId != null && !deliveryId.isBlank())
            ? "gh-delivery:" + tenant.getId() + ":" + deliveryId.trim()
            : null;
        String payloadKey = "gh-payload:" + tenant.getId() + ":" + payloadHash;

        if (deliveryKey != null && !idempotencyService.tryClaimEvent(deliveryKey, tenant.getId(), "GITHUB_WEBHOOK")) {
            recordWebhookMetric(eventName, "duplicate");
            return ResponseEntity.ok(Map.of("status", "DUPLICATE_IGNORED", "deliveryId", deliveryKey));
        }
        if (!idempotencyService.tryClaimEvent(payloadKey, tenant.getId(), "GITHUB_WEBHOOK_PAYLOAD")) {
            if (deliveryKey != null) {
                idempotencyService.markCompleted(deliveryKey);
            }
            recordWebhookMetric(eventName, "duplicate");
            return ResponseEntity.ok(Map.of("status", "DUPLICATE_IGNORED", "deliveryId", deliveryKey != null ? deliveryKey : payloadKey));
        }

        try {
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

            String diff = "";
            if (allowInlineDiff) {
                diff = root.path("diff").asText(pr.path("diff").asText(""));
            }
            if (diff.isBlank()) {
                String ghToken = authService.resolveTenantGithubToken(tenant);
                var snapshot = githubIngestionClient.fetchPullRequest(repo.getOwner(), repo.getName(), prNumber, ghToken);
                diff = snapshot.rawDiff();
            }

            ReviewDtos.ReviewSummaryDto review = reviewPipelineService.enqueueReviewForTenant(
                tenant.getId(),
                author + "@github",
                "WEBHOOK",
                new ReviewDtos.CreateReviewRequest(repo.getId(), prNumber, title, author, headRef, baseRef, sha, diff)
            );

            if (deliveryKey != null) {
                idempotencyService.markCompleted(deliveryKey);
            }
            idempotencyService.markCompleted(payloadKey);
            recordWebhookMetric(eventName, "accepted");

            return ResponseEntity.accepted().body(Map.of(
                "status", "ACCEPTED",
                "reviewId", review.id(),
                "repository", repo.getFullName(),
                "prNumber", prNumber
            ));
        } catch (Exception ex) {
            // Release in-progress idempotency claims so legitimate GitHub webhook redeliveries succeed after transient failures
            if (deliveryKey != null) {
                idempotencyService.releaseClaim(deliveryKey);
            }
            idempotencyService.releaseClaim(payloadKey);
            recordWebhookMetric(eventName, "failed");
            throw ex;
        }
    }

    private void recordWebhookMetric(String eventName, String outcome) {
        String safeEvent = (eventName == null || eventName.isBlank()) ? "unknown" : eventName.toLowerCase();
        meterRegistry.counter("forge.webhooks.processed", "event", safeEvent, "outcome", outcome).increment();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
