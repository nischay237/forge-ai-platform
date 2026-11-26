package com.forge.platform.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.common.SecretCryptoService;
import com.forge.platform.repository.GithubIngestionClient;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class TenantIsolationAndWebhookTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SecretCryptoService cryptoService;
    @Autowired private GithubIngestionClient githubIngestionClient;

    @Test
    void enforcesPerTenantWebhookSecretsSlugAntiEnumerationAndTokenIsolation() throws Exception {
        String suf = UUID.randomUUID().toString().substring(0, 6);
        JsonNode tenantA = register("Iso Alpha " + suf, "iso-a-" + suf + "@forge.test");
        JsonNode tenantB = register("Iso Beta " + suf, "iso-b-" + suf + "@forge.test");

        String tokenA = tenantA.path("accessToken").asText();
        String tokenB = tenantB.path("accessToken").asText();
        String slugA = tenantA.path("tenant").path("slug").asText();
        String secretA = tenantA.path("initialWebhookSecret").asText();
        String secretB = tenantB.path("initialWebhookSecret").asText();

        // Tenant A saves a GitHub PAT -> Tenant B must STILL see githubTokenConfigured=false
        mockMvc.perform(patch("/api/v1/settings")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("newGithubToken", "ghp_tenantA_only_token_123456"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.githubTokenConfigured").value(true));

        mockMvc.perform(get("/api/v1/settings")
                .header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tenant.githubTokenConfigured").value(false))
            .andExpect(jsonPath("$.tenant.maskedGithubToken").value(""));

        // Unknown tenant slug returns uniform 401 (no 404 slug enumeration oracle)
        String body = "{\"action\":\"opened\"}";
        mockMvc.perform(post("/api/v1/webhooks/github/nonexistent-tenant-xyz")
                .header("X-Hub-Signature-256", "sha256=deadbeef")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"));

        // Tenant B's secret cannot sign Tenant A's webhook
        String sigWithB = "sha256=" + cryptoService.hmacSha256Hex(body, secretB);
        mockMvc.perform(post("/api/v1/webhooks/github/" + slugA)
                .header("X-Hub-Signature-256", sigWithB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());

        // Rotating Tenant A's webhook secret invalidates old secret and activates new one
        String rotJson = mockMvc.perform(post("/api/v1/settings/rotate-webhook-secret")
                .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String rotatedSecretA = objectMapper.readTree(rotJson).path("webhookSecret").asText();
        assertThat(rotatedSecretA).startsWith("whsec_").isNotEqualTo(secretA);

        String sigWithOldA = "sha256=" + cryptoService.hmacSha256Hex(body, secretA);
        mockMvc.perform(post("/api/v1/webhooks/github/" + slugA)
                .header("X-Hub-Signature-256", sigWithOldA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsPathTraversalSegmentsInGithubClient() {
        assertThatThrownBy(() -> githubIngestionClient.fetchPullRequest("..", "repo", 1, ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> githubIngestionClient.fetchPullRequest("owner", ".", 1, ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> githubIngestionClient.fetchPullRequest("owner", "a..b", 1, ""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private JsonNode register(String workspace, String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "workspaceName", workspace,
            "fullName", workspace + " Owner",
            "email", email,
            "password", "StrongPass#2026"
        ));
        String resp = mockMvc.perform(post("/api/v1/auth/register")
                .header("X-Forwarded-For", "10.30.40." + Math.abs(email.hashCode() % 200))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }
}
