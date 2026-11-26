package com.forge.platform.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.RepositoryJpaRepository;
import com.forge.platform.review.PullRequestReviewEntity;
import com.forge.platform.review.PullRequestReviewRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class SseOperationTrackingAndOrderingTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SseHubService sseHubService;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private RepositoryJpaRepository repositoryJpaRepository;
    @Autowired private PullRequestReviewRepository reviewRepository;

    private record TenantAuth(String token, String tenantId) {}

    private TenantAuth register(String name) throws Exception {
        String suf = UUID.randomUUID().toString().substring(0, 6);
        String resp = mockMvc.perform(post("/api/v1/auth/register")
                .header("X-Forwarded-For", "10.66.0." + Math.abs(suf.hashCode() % 200))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "workspaceName", name + " " + suf,
                    "fullName", name + " Admin",
                    "email", name.toLowerCase() + "-" + suf + "@forge.test",
                    "password", "StrongPass#2026"
                ))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(resp);
        return new TenantAuth(node.path("accessToken").asText(), node.path("tenant").path("id").asText());
    }

    @Test
    void tracksMonotonicOrderedStagesEnforcesTenantIsolationAndSupportsRefreshRecovery() throws Exception {
        TenantAuth tenantA = register("SseAlpha");
        TenantAuth tenantB = register("SseBeta");
        String reviewId = "rev-" + UUID.randomUUID().toString().substring(0, 8);
        String opId = PipelineEvent.buildOperationId("PR_REVIEW", reviewId);

        PipelineEvent e1 = sseHubService.publish(tenantA.tenantId(), PipelineEvent.stage(
            "PR_REVIEW", reviewId, "QUEUED", "Request accepted & queued", "STARTED", 1, 3,
            "Queued PR #12", Map.of("prNumber", 12)
        ));
        PipelineEvent e2 = sseHubService.publish(tenantA.tenantId(), PipelineEvent.stage(
            "PR_REVIEW", reviewId, "INGESTING_DIFF", "Parsing diff", "IN_PROGRESS", 2, 3,
            "Parsing 4 files", Map.of("changedFiles", 4)
        ));
        PipelineEvent e3 = sseHubService.publish(tenantA.tenantId(), PipelineEvent.stage(
            "PR_REVIEW", reviewId, "COMPLETED", "Review complete", "COMPLETED", 3, 3,
            "Completed [WARN]", Map.of("mergeDecision", "WARN", "riskScore", 42)
        ));

        assertThat(e1.sequence()).isEqualTo(1L);
        assertThat(e2.sequence()).isEqualTo(2L);
        assertThat(e3.sequence()).isEqualTo(3L);

        // Tenant A recovers exact ordered operation snapshot via REST recovery endpoint
        mockMvc.perform(get("/api/v1/stream/operations/" + opId)
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.operationId").value(opId))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.completedStages").value(3))
            .andExpect(jsonPath("$.totalStages").value(3))
            .andExpect(jsonPath("$.latestSequence").value(3))
            .andExpect(jsonPath("$.events.length()").value(3))
            .andExpect(jsonPath("$.events[0].stage").value("QUEUED"))
            .andExpect(jsonPath("$.events[1].stage").value("INGESTING_DIFF"))
            .andExpect(jsonPath("$.events[2].stage").value("COMPLETED"))
            .andExpect(jsonPath("$.events[2].details.mergeDecision").value("WARN"));

        // Resource filter returns the matching operation for Tenant A
        mockMvc.perform(get("/api/v1/stream/operations").param("resourceId", reviewId)
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].operationId").value(opId));

        // Tenant B MUST NOT access Tenant A's operation snapshot (strict 404 isolation)
        mockMvc.perform(get("/api/v1/stream/operations/" + opId)
                .header("Authorization", "Bearer " + tenantB.token()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/stream/operations").param("resourceId", reviewId)
                .header("Authorization", "Bearer " + tenantB.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        // Unauthenticated requests are rejected with 401
        mockMvc.perform(get("/api/v1/stream/operations/" + opId))
            .andExpect(status().isUnauthorized());

        // Restarting a completed operation with a fresh STARTED event cleanly resets sequence to 1
        PipelineEvent restart = sseHubService.publish(tenantA.tenantId(), PipelineEvent.stage(
            "PR_REVIEW", reviewId, "QUEUED", "Re-queued review", "STARTED", 1, 3,
            "Restarted PR #12", Map.of()
        ));
        assertThat(restart.sequence()).isEqualTo(1L);

        // SSE active connections gauge is registered in Micrometer
        assertThat(meterRegistry.find("forge.sse.connections.active").gauge()).isNotNull();
    }

    @Test
    void recoversPersistedOperationStateFromDatabaseAfterRestartAndEmitsEvaluationStages() throws Exception {
        TenantAuth tenantA = register("FallbackAlpha");
        TenantAuth tenantB = register("FallbackBeta");

        // Persist a repository and completed PR review directly in DB without publishing any in-memory SSE events
        // (simulating a browser refresh after server restart or cross-replica routing).
        RepositoryEntity repo = RepositoryEntity.create(
            tenantA.tenantId(), "acme", "billing-core-" + UUID.randomUUID().toString().substring(0, 4),
            "main", "Java", "https://github.com/acme/billing-core", "Billing service"
        );
        repo.setIndexStatus("INDEXED");
        repo.setFileCount(12);
        repo.setSymbolCount(48);
        repo.setDependencyCount(31);
        repositoryJpaRepository.save(repo);

        PullRequestReviewEntity review = PullRequestReviewEntity.queued(
            tenantA.tenantId(), repo.getId(), 91, "Harden ledger idempotency",
            "eng@acme.dev", "feat/ledger", "main", "abc123def456",
            "diff --git a/Ledger.java b/Ledger.java\n--- a/Ledger.java\n+++ b/Ledger.java\n@@ -1 +1,2 @@\n+int x = 1;\n",
            "v1"
        );
        review.setStatus("COMPLETED");
        review.setCurrentStage("COMPLETED");
        review.setRiskScore(38);
        review.setMergeDecision("WARN");
        reviewRepository.save(review);

        String reviewOpId = PipelineEvent.buildOperationId("PR_REVIEW", review.getId());
        String repoOpId = PipelineEvent.buildOperationId("REPO_INDEX", repo.getId());

        // Tenant A recovers persisted PR_REVIEW and REPO_INDEX snapshots cleanly despite empty in-memory SSE buffer
        mockMvc.perform(get("/api/v1/stream/operations/" + reviewOpId)
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.operationId").value(reviewOpId))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.completedStages").value(8))
            .andExpect(jsonPath("$.events[0].details.recoveredFromDatabase").value(true))
            .andExpect(jsonPath("$.events[0].details.mergeDecision").value("WARN"));

        mockMvc.perform(get("/api/v1/stream/operations").param("resourceId", repo.getId())
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].operationId").value(repoOpId))
            .andExpect(jsonPath("$[0].status").value("COMPLETED"))
            .andExpect(jsonPath("$[0].events[0].details.recoveredFromDatabase").value(true));

        // Tenant B MUST NOT recover Tenant A's persisted operations (strict cross-tenant isolation)
        mockMvc.perform(get("/api/v1/stream/operations/" + reviewOpId)
                .header("Authorization", "Bearer " + tenantB.token()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/stream/operations").param("resourceId", repo.getId())
                .header("Authorization", "Bearer " + tenantB.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        // Running chronological held-out evaluation emits all 4 PREDICTION_EVALUATION SSE stages
        mockMvc.perform(post("/api/v1/predictions/evaluations/run")
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/stream/operations/PREDICTION_EVALUATION:workspace")
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.totalStages").value(4))
            .andExpect(jsonPath("$.events.length()").value(4))
            .andExpect(jsonPath("$.events[0].stage").value("LOADING_PREDICTION_HISTORY"))
            .andExpect(jsonPath("$.events[1].stage").value("LOADING_CONFIRMED_OUTCOMES"))
            .andExpect(jsonPath("$.events[2].stage").value("EVALUATING_HELD_OUT_SPLITS"))
            .andExpect(jsonPath("$.events[3].stage").value("EVALUATION_COMPLETED"));
    }
}
