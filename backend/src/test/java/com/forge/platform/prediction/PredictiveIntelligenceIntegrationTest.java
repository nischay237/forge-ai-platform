package com.forge.platform.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.common.SecretCryptoService;
import com.forge.platform.identity.AuthService;
import com.forge.platform.identity.Role;
import com.forge.platform.identity.Tenant;
import com.forge.platform.identity.TenantRepository;
import com.forge.platform.identity.User;
import com.forge.platform.identity.UserRepository;
import com.forge.platform.identity.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
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
class PredictiveIntelligenceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private AuthService authService;
    @Autowired private SecretCryptoService cryptoService;

    private record TenantSession(String token, String tenantId, String slug) {}

    private TenantSession registerWorkspace(String prefix) throws Exception {
        String suf = UUID.randomUUID().toString().substring(0, 6);
        String resp = mockMvc.perform(post("/api/v1/auth/register")
                .header("X-Forwarded-For", "10.88." + Math.abs(prefix.hashCode() % 200) + "." + Math.abs(suf.hashCode() % 200))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "workspaceName", prefix + " " + suf,
                    "fullName", prefix + " Owner",
                    "email", prefix.toLowerCase() + "-" + suf + "@forge.test",
                    "password", "StrongPass#2026"
                ))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(resp);
        return new TenantSession(
            node.path("accessToken").asText(),
            node.path("tenant").path("id").asText(),
            node.path("tenant").path("slug").asText()
        );
    }

    private String connectSampleRepo(String token, String name) throws Exception {
        String serviceCode = """
            package com.acme.payments;
            public class LedgerService {
                public void post(String id) {
                    if (id == null) throw new IllegalArgumentException();
                }
            }
            """;
        String testCode = """
            package com.acme.payments;
            public class LedgerServiceTest {
                void shouldPost() {}
            }
            """;
        String resp = mockMvc.perform(post("/api/v1/repositories")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "owner", "acme-org",
                    "name", name,
                    "defaultBranch", "main",
                    "primaryLanguage", "Java",
                    "fetchFromGithub", false,
                    "initialFiles", Map.of(
                        "src/main/java/com/acme/payments/LedgerService.java", serviceCode,
                        "src/test/java/com/acme/payments/LedgerServiceTest.java", testCode
                    )
                ))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("id").asText();
    }

    private String triggerPrReview(String token, String repoId, int prNumber, String rawDiff) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/reviews")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "repositoryId", repoId,
                    "prNumber", prNumber,
                    "title", "feat: update ledger PR #" + prNumber,
                    "sourceBranch", "feat/pr-" + prNumber,
                    "targetBranch", "main",
                    "rawDiff", rawDiff
                ))))
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("id").asText();
    }

    @Test
    void fullPredictiveFlowIdempotentIngestionWebhookTenantIsolationRbacAndEvaluation() throws Exception {
        TenantSession tenantA = registerWorkspace("PredAlpha");
        TenantSession tenantB = registerWorkspace("PredBeta");

        String repoName = "ledger-core-" + UUID.randomUUID().toString().substring(0, 5);
        String repoId = connectSampleRepo(tenantA.token(), repoName);

        String diffWithPerf = """
            diff --git a/src/main/java/com/acme/payments/LedgerService.java b/src/main/java/com/acme/payments/LedgerService.java
            --- a/src/main/java/com/acme/payments/LedgerService.java
            +++ b/src/main/java/com/acme/payments/LedgerService.java
            @@ -1,4 +1,11 @@
             public class LedgerService {
                 public void post(String id) {
            +        for (int i = 0; i < 50; i++) {
            +            ledgerRepository.findById(id + i);
            +        }
            +        if (id == null) throw new IllegalArgumentException();
                 }
             }
            """;

        String reviewId = triggerPrReview(tenantA.token(), repoId, 101, diffWithPerf);

        // 1. Missing historical data baseline: returns honest insufficient-data flags and reuses unchanged commit run
        String firstPredJson = mockMvc.perform(get("/api/v1/predictions/" + reviewId)
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reviewId").value(reviewId))
            .andExpect(jsonPath("$.historicalSampleSize").value(0))
            .andExpect(jsonPath("$.signalChannels.length()").value(7))
            .andExpect(jsonPath("$.predictions.length()").value(5))
            .andReturn().getResponse().getContentAsString();
        JsonNode firstPred = objectMapper.readTree(firstPredJson);
        String firstRunId = firstPred.path("predictionRunId").asText();
        int initialScore = firstPred.path("overallEngineeringRiskScore").asInt();

        // Calling GET again for unchanged commit SHA reuses the exact persisted run (no redundant re-analysis)
        mockMvc.perform(get("/api/v1/predictions/" + reviewId)
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.predictionRunId").value(firstRunId));

        // 2. Idempotent historical ingestion + duplicate key handling + validation of invalid enum, malformed JSON & oversized fields
        mockMvc.perform(post("/api/v1/predictions/history/tests")
                .header("Authorization", "Bearer " + tenantA.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ invalid json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"));

        mockMvc.perform(post("/api/v1/predictions/history/defects")
                .header("Authorization", "Bearer " + tenantA.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "repositoryId", repoId,
                    "title", "X".repeat(300),
                    "severity", "HIGH"
                ))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/predictions/history/tests")
                .header("Authorization", "Bearer " + tenantA.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "repositoryId", repoId,
                    "prNumber", 101,
                    "testName", "LedgerServiceTest",
                    "outcome", "BOGUS_OUTCOME"
                ))))
            .andExpect(status().isBadRequest());

        Map<String, Object> testPayload = Map.of(
            "repositoryId", repoId,
            "prNumber", 101,
            "testName", "LedgerServiceTest",
            "testFilePath", "src/test/java/com/acme/payments/LedgerServiceTest.java",
            "moduleName", "payments",
            "outcome", "FAILED",
            "durationMs", 420,
            "errorSummary", "AssertionError: expected balance 100",
            "externalEventKey", "ci-test-101-run-1"
        );
        mockMvc.perform(post("/api/v1/predictions/history/tests")
                .header("Authorization", "Bearer " + tenantA.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testPayload)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.duplicate").value(false));

        // Duplicate submission with identical externalEventKey -> 200 OK duplicate=true
        mockMvc.perform(post("/api/v1/predictions/history/tests")
                .header("Authorization", "Bearer " + tenantA.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testPayload)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(true));

        // Ingest confirmed defect, confirmed incident, and rollback deployment
        mockMvc.perform(post("/api/v1/predictions/history/defects")
                .header("Authorization", "Bearer " + tenantA.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "repositoryId", repoId,
                    "title", "Ledger race defect",
                    "severity", "HIGH",
                    "moduleName", "payments",
                    "filePaths", List.of("src/main/java/com/acme/payments/LedgerService.java"),
                    "introducedPrNumber", 101,
                    "externalEventKey", "def-101"
                ))))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/predictions/history/incidents")
                .header("Authorization", "Bearer " + tenantA.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "repositoryId", repoId,
                    "title", "Ledger settlement timeout",
                    "severity", "SEV2",
                    "serviceName", repoName,
                    "affectedModules", List.of("payments"),
                    "relatedPrNumber", 101,
                    "externalEventKey", "inc-101"
                ))))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/predictions/history/deployments")
                .header("Authorization", "Bearer " + tenantA.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "repositoryId", repoId,
                    "serviceName", repoName,
                    "environment", "production",
                    "prNumber", 101,
                    "outcome", "ROLLED_BACK",
                    "changedConfig", true,
                    "summary", "Rolled back after latency spike",
                    "externalEventKey", "dep-rb-101"
                ))))
            .andExpect(status().isCreated());

        // 3. Signed GitHub deployment_status webhook ingests real deployment & blocks replay
        Tenant tenantEntity = tenantRepository.findById(tenantA.tenantId()).orElseThrow();
        String webhookSecret = authService.resolveTenantWebhookSecret(tenantEntity);
        String webhookBody = objectMapper.writeValueAsString(Map.of(
            "repository", Map.of("full_name", "acme-org/" + repoName),
            "deployment", Map.of("environment", "production", "sha", "deadbeef1234"),
            "deployment_status", Map.of("state", "failure", "description", "Deployment failed healthcheck")
        ));
        String sig = "sha256=" + cryptoService.hmacSha256Hex(webhookBody, webhookSecret);
        mockMvc.perform(post("/api/v1/webhooks/github/" + tenantA.slug())
                .header("X-GitHub-Event", "deployment_status")
                .header("X-GitHub-Delivery", "deliv-deploy-001")
                .header("X-Hub-Signature-256", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .content(webhookBody))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("INGESTED"))
            .andExpect(jsonPath("$.recordType").value("DEPLOYMENT"));

        // Replaying the same webhook delivery is idempotently ignored
        mockMvc.perform(post("/api/v1/webhooks/github/" + tenantA.slug())
                .header("X-GitHub-Event", "deployment_status")
                .header("X-GitHub-Delivery", "deliv-deploy-001")
                .header("X-Hub-Signature-256", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .content(webhookBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DUPLICATE_IGNORED"));

        // Verify CI Diagnosis -> Historical Test Execution automatic bridge
        mockMvc.perform(post("/api/v1/ci/builds")
                .header("Authorization", "Bearer " + tenantA.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "repositoryId", repoId,
                    "pipelineName", "ledger-pr-ci",
                    "branch", "feat/pr-101",
                    "prNumber", 101,
                    "status", "FAILED",
                    "rawLog", "[ERROR] Failures: LedgerServiceIT.shouldReconcile failed with AssertionError"
                ))))
            .andExpect(status().isCreated());

        // 4. Recompute prediction now reflects real historical samples and higher risk
        String recomputedJson = mockMvc.perform(post("/api/v1/predictions/" + reviewId + "/run")
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.historicalSampleSize").value(7))
            .andReturn().getResponse().getContentAsString();
        JsonNode recomputed = objectMapper.readTree(recomputedJson);
        assertThat(recomputed.path("overallEngineeringRiskScore").asInt()).isGreaterThanOrEqualTo(initialScore);

        // Verify /runs returns versioned prediction run history (initial + recomputed)
        mockMvc.perform(get("/api/v1/predictions/" + reviewId + "/runs")
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        // 5. Verify /evidence, /tests (predicted vs actual separated including CI bridge), /history, and /overview
        mockMvc.perform(get("/api/v1/predictions/" + reviewId + "/evidence")
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.byFile.length()").isNotEmpty())
            .andExpect(jsonPath("$.byModule.length()").isNotEmpty())
            .andExpect(jsonPath("$.byService.length()").isNotEmpty())
            .andExpect(jsonPath("$.evidence.length()").isNotEmpty());

        mockMvc.perform(get("/api/v1/predictions/" + reviewId + "/tests")
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasActualCiResults").value(true))
            .andExpect(jsonPath("$.predictedTestsAtRisk.length()").isNotEmpty())
            .andExpect(jsonPath("$.actualCiTestResults.length()").value(2))
            .andExpect(jsonPath("$.actualCiTestResults[0].outcome").value("FAILED"));

        mockMvc.perform(get("/api/v1/predictions/" + reviewId + "/history")
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.relatedDefects.length()").value(1))
            .andExpect(jsonPath("$.relatedIncidents.length()").value(1))
            .andExpect(jsonPath("$.relatedDeployments.length()").value(2));

        mockMvc.perform(get("/api/v1/predictions/overview")
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalPredictions").value(1))
            .andExpect(jsonPath("$.totalDeployments").value(2))
            .andExpect(jsonPath("$.totalRollbacks").value(1))
            .andExpect(jsonPath("$.totalConfirmedIncidents").value(1))
            .andExpect(jsonPath("$.totalConfirmedDefects").value(1));

        // 6. Cross-Tenant Isolation & Unauthenticated / VIEWER RBAC
        mockMvc.perform(get("/api/v1/predictions/" + reviewId))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/predictions/" + reviewId)
                .header("Authorization", "Bearer " + tenantB.token()))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/predictions/" + reviewId + "/evidence")
                .header("Authorization", "Bearer " + tenantB.token()))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/predictions/history/defects")
                .header("Authorization", "Bearer " + tenantB.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "repositoryId", repoId,
                    "title", "Cross-tenant attempt",
                    "severity", "LOW"
                ))))
            .andExpect(status().isNotFound());

        User viewer = userRepository.save(User.create(
            tenantA.tenantId(),
            "viewer-" + UUID.randomUUID().toString().substring(0, 6) + "@forge.test",
            "Read Only Viewer",
            passwordEncoder.encode("ViewerPass#2026"),
            Role.VIEWER
        ));
        String viewerToken = jwtService.generateToken(viewer);

        mockMvc.perform(get("/api/v1/predictions/" + reviewId)
                .header("Authorization", "Bearer " + viewerToken))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/predictions/" + reviewId + "/run")
                .header("Authorization", "Bearer " + viewerToken))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/predictions/history/defects")
                .header("Authorization", "Bearer " + viewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "repositoryId", repoId,
                    "title", "Viewer should not ingest",
                    "severity", "LOW"
                ))))
            .andExpect(status().isForbidden());

        // 7. Chronological Held-Out Model Evaluation:
        //    (a) With < 5 PRs -> INSUFFICIENT_LABELLED_DATA and NULL precision/F1 (never fabricated)
        String earlyEvalJson = mockMvc.perform(post("/api/v1/predictions/evaluations/run")
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode earlyEvals = objectMapper.readTree(earlyEvalJson);
        assertThat(earlyEvals.size()).isEqualTo(4);
        for (JsonNode ev : earlyEvals) {
            assertThat(ev.path("status").asText()).isEqualTo("INSUFFICIENT_LABELLED_DATA");
            assertThat(ev.path("precisionScore").isNull()).isTrue();
            assertThat(ev.path("f1Score").isNull()).isTrue();
        }

        //    (b) Add 4 more PRs + actual CI test results (total 5 matched PRs) -> TEST_FAILURE_RISK evaluates chronologically!
        for (int pr = 102; pr <= 105; pr++) {
            String rev = triggerPrReview(tenantA.token(), repoId, pr, diffWithPerf);
            mockMvc.perform(post("/api/v1/predictions/" + rev + "/run")
                    .header("Authorization", "Bearer " + tenantA.token()))
                .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/predictions/history/tests")
                    .header("Authorization", "Bearer " + tenantA.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "repositoryId", repoId,
                        "prNumber", pr,
                        "testName", "LedgerServiceTest",
                        "outcome", pr % 2 == 0 ? "FAILED" : "PASSED",
                        "externalEventKey", "ci-test-eval-" + pr
                    ))))
                .andExpect(status().isCreated());
        }

        String evalJson = mockMvc.perform(post("/api/v1/predictions/evaluations/run")
                .header("Authorization", "Bearer " + tenantA.token()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode evals = objectMapper.readTree(evalJson);
        JsonNode testEval = null;
        for (JsonNode ev : evals) {
            if ("TEST_FAILURE_RISK".equals(ev.path("category").asText())) {
                testEval = ev;
            }
        }
        assertThat(testEval).isNotNull();
        assertThat(testEval.path("status").asText()).isEqualTo("EVALUATED");
        assertThat(testEval.path("sampleSize").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(testEval.path("precisionScore").isNull()).isFalse();
        assertThat(testEval.path("calibrationError").isNull()).isFalse();
    }
}
