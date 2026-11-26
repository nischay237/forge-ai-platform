package com.forge.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.common.SecretCryptoService;
import com.forge.platform.review.ReviewPipelineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class ForgePlatformIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SecretCryptoService cryptoService;
    @Autowired private ReviewPipelineService reviewPipelineService;

    @Test
    void fullMultiTenantSecurityWebhookAstReviewAndEvaluationFlow() throws Exception {
        // 1. Register Tenant A and Tenant B (each receives its own unique CSPRNG webhook secret)
        JsonNode tenantA = registerWorkspace("Alpha Systems", "alpha@acme.io", "StrongPass#2026");
        String tokenA = tenantA.path("accessToken").asText();
        String tenantAId = tenantA.path("tenant").path("id").asText();
        String tenantASlug = tenantA.path("tenant").path("slug").asText();
        String tenantAWebhookSecret = tenantA.path("initialWebhookSecret").asText();
        assertThat(tenantAWebhookSecret).startsWith("whsec_").hasSizeGreaterThan(24);

        JsonNode tenantB = registerWorkspace("Beta Corp", "beta@corp.io", "StrongPass#2026");
        String tokenB = tenantB.path("accessToken").asText();
        String tenantBWebhookSecret = tenantB.path("initialWebhookSecret").asText();
        assertThat(tenantBWebhookSecret).isNotEqualTo(tenantAWebhookSecret);

        // 2. Tenant A connects a repository with real Java files
        String connectPayload = objectMapper.writeValueAsString(Map.of(
            "owner", "alpha-org",
            "name", "billing-core",
            "defaultBranch", "main",
            "primaryLanguage", "Java",
            "fetchFromGithub", false
        ));
        String repoJson = mockMvc.perform(post("/api/v1/repositories")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(connectPayload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.fullName").value("alpha-org/billing-core"))
            .andReturn().getResponse().getContentAsString();
        String repoId = objectMapper.readTree(repoJson).path("id").asText();

        // 3. Enforce Multi-Tenant Isolation: Tenant B MUST NOT read Tenant A's repository files
        mockMvc.perform(get("/api/v1/repositories/" + repoId + "/files")
                .header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isNotFound());

        // 4. Signed GitHub Webhook Verification + Idempotency Deduplication
        String webhookBody = """
            {
              "action": "opened",
              "repository": { "full_name": "alpha-org/billing-core" },
              "pull_request": {
                "number": 42,
                "title": "feat: add invoice query",
                "user": { "login": "dev-alpha" },
                "head": { "ref": "feat/invoice", "sha": "abc12345" },
                "base": { "ref": "main" }
              },
              "diff": "diff --git a/src/main/java/InvoiceDao.java b/src/main/java/InvoiceDao.java\\n+++ b/src/main/java/InvoiceDao.java\\n@@ -1,1 +1,3 @@\\n+String sql = \\"SELECT * FROM invoices WHERE id = '\\" + id + \\"'\\";\\n+private static final SimpleDateFormat FMT = new SimpleDateFormat(\\"yyyy\\");\\n"
            }
            """;

        // Reject invalid signature
        mockMvc.perform(post("/api/v1/webhooks/github/" + tenantASlug)
                .header("X-Hub-Signature-256", "sha256=deadbeef")
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "delivery-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(webhookBody))
            .andExpect(status().isUnauthorized());

        // Accept valid HMAC-SHA256 signature signed with Tenant A's unique secret
        String validSig = "sha256=" + cryptoService.hmacSha256Hex(webhookBody, tenantAWebhookSecret);
        String webhookResp = mockMvc.perform(post("/api/v1/webhooks/github/" + tenantASlug)
                .header("X-Hub-Signature-256", validSig)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "delivery-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(webhookBody))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andReturn().getResponse().getContentAsString();
        String reviewId = objectMapper.readTree(webhookResp).path("reviewId").asText();

        // Replay same X-GitHub-Delivery -> deduplicated idempotently!
        mockMvc.perform(post("/api/v1/webhooks/github/" + tenantASlug)
                .header("X-Hub-Signature-256", validSig)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "delivery-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(webhookBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DUPLICATE_IGNORED"));

        // 5. Execute Review Pipeline deterministically & inspect findings + approval safety guard
        reviewPipelineService.executeReviewPipeline(tenantAId, reviewId, false);

        String detailJson = mockMvc.perform(get("/api/v1/reviews/" + reviewId)
                .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.review.status").value("COMPLETED"))
            .andReturn().getResponse().getContentAsString();
        JsonNode detail = objectMapper.readTree(detailJson);
        assertThat(detail.path("findings").size()).isGreaterThanOrEqualTo(2);
        String firstFindingId = detail.path("findings").get(0).path("id").asText();

        // Tenant B cannot view Tenant A's review
        mockMvc.perform(get("/api/v1/reviews/" + reviewId)
                .header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isNotFound());

        // Human-in-the-loop guard blocks publish before any finding is approved
        mockMvc.perform(post("/api/v1/reviews/" + reviewId + "/publish")
                .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("APPROVAL_REQUIRED"));

        // Approve finding -> publish succeeds
        mockMvc.perform(patch("/api/v1/reviews/" + reviewId + "/findings/" + firstFindingId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/api/v1/reviews/" + reviewId + "/publish")
                .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publishedToGithub").value(true));

        // 6. Run Reproducible Evaluation Benchmark (12 cases)
        String evalJson = mockMvc.perform(post("/api/v1/reviews/evaluations/run")
                .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.datasetSize").value(12))
            .andReturn().getResponse().getContentAsString();
        JsonNode eval = objectMapper.readTree(evalJson);
        assertThat(eval.path("precision").asDouble()).isGreaterThanOrEqualTo(0.95);
        assertThat(eval.path("recall").asDouble()).isGreaterThanOrEqualTo(0.95);
        assertThat(eval.path("falsePositiveRate").asDouble()).isEqualTo(0.0);

        // 7. CI Log Diagnosis
        String ciPayload = objectMapper.writeValueAsString(Map.of(
            "repositoryId", repoId,
            "pipelineName", "ci/flyway-check",
            "branch", "feat/invoice",
            "status", "FAILED",
            "rawLog", "FlywayValidateException: Migration checksum mismatch for migration version 2"
        ));
        mockMvc.perform(post("/api/v1/ci/builds")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ciPayload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.failureCategory").value("MIGRATION_DRIFT"));

        // 8. Overview Analytics reflects real DB counts
        mockMvc.perform(get("/api/v1/analytics/overview")
                .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connectedRepositories").value(1))
            .andExpect(jsonPath("$.totalPrReviews").value(1))
            .andExpect(jsonPath("$.totalCiBuilds").value(1));
    }

    private JsonNode registerWorkspace(String workspace, String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "workspaceName", workspace,
            "fullName", workspace + " Owner",
            "email", email,
            "password", password
        ));
        String resp = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }
}
