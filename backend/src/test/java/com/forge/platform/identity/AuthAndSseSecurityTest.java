package com.forge.platform.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
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
class AuthAndSseSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void refreshRotationReuseDetectionLogoutAndReadOnlyMe() throws Exception {
        String email = "rotator-" + UUID.randomUUID().toString().substring(0, 8) + "@forge.test";
        JsonNode reg = register("Rotation Org", email, "SecurePass#2026");

        String accessToken = reg.path("accessToken").asText();
        String refresh1 = reg.path("refreshToken").asText();
        assertThat(refresh1).startsWith("frt_");

        // GET /me must be read-only (never mints accessToken)
        String meJson = mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.email").value(email))
            .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(meJson).has("accessToken")).isFalse();

        // Rotate refresh1 -> refresh2
        String refreshResp = mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh1))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode rotated = objectMapper.readTree(refreshResp);
        String access2 = rotated.path("accessToken").asText();
        String refresh2 = rotated.path("refreshToken").asText();
        assertThat(refresh2).isNotEqualTo(refresh1);

        // Replaying revoked refresh1 triggers reuse detection and revokes the entire family (including refresh2!)
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh1))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSE_DETECTED"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh2))))
            .andExpect(status().isUnauthorized());

        // Logout immediately revokes the active JWT jti
        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + access2)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + access2))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void bruteForceLockoutLocksAccountAfterFiveFailuresAndRecordsAudit() throws Exception {
        String email = "lockout-" + UUID.randomUUID().toString().substring(0, 8) + "@forge.test";
        JsonNode reg = register("Lockout Org", email, "CorrectPass#2026");
        String tenantId = reg.path("tenant").path("id").asText();

        String badLogin = objectMapper.writeValueAsString(Map.of("email", email, "password", "WrongPass#123"));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", "10.0.1." + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(badLogin))
                .andExpect(status().isUnauthorized());
        }

        // 6th attempt must return 423 LOCKED even with the right password!
        String goodLogin = objectMapper.writeValueAsString(Map.of("email", email, "password", "CorrectPass#2026"));
        mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", "10.0.1.99")
                .contentType(MediaType.APPLICATION_JSON)
                .content(goodLogin))
            .andExpect(status().isLocked())
            .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        var logs = auditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(0, 20));
        assertThat(logs).anyMatch(l -> "USER_ACCOUNT_LOCKED".equals(l.getAction()));
    }

    @Test
    void sseRejectsJwtQueryParamAndEnforcesSingleUseTickets() throws Exception {
        String email = "sse-" + UUID.randomUUID().toString().substring(0, 8) + "@forge.test";
        JsonNode reg = register("SSE Org", email, "SecurePass#2026");
        String token = reg.path("accessToken").asText();

        // Legacy ?token=<jwt> MUST be rejected with 401
        mockMvc.perform(get("/api/v1/stream/events").param("token", token))
            .andExpect(status().isUnauthorized());

        // Mint single-use ticket via authenticated POST
        String ticketResp = mockMvc.perform(post("/api/v1/stream/ticket")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expiresInSeconds").value(30))
            .andReturn().getResponse().getContentAsString();
        String ticket = objectMapper.readTree(ticketResp).path("ticket").asText();
        assertThat(ticket).startsWith("sse_");

        // First use succeeds
        mockMvc.perform(get("/api/v1/stream/events").param("ticket", ticket))
            .andExpect(status().isOk());

        // Replay of same ticket fails with 401
        mockMvc.perform(get("/api/v1/stream/events").param("ticket", ticket))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_SSE_TICKET"));
    }

    @Test
    void rbacBlocksReviewerFromSettingsAndAuditLogs() throws Exception {
        String ownerEmail = "rbac-owner-" + UUID.randomUUID().toString().substring(0, 8) + "@forge.test";
        JsonNode reg = register("RBAC Org", ownerEmail, "SecurePass#2026");
        String tenantId = reg.path("tenant").path("id").asText();

        String reviewerEmail = "rbac-rev-" + UUID.randomUUID().toString().substring(0, 8) + "@forge.test";
        userRepository.save(User.create(tenantId, reviewerEmail, "Reviewer User",
            passwordEncoder.encode("ReviewerPass#2026"), Role.REVIEWER));

        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", "10.10.10.2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", reviewerEmail, "password", "ReviewerPass#2026"))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String reviewerToken = objectMapper.readTree(loginResp).path("accessToken").asText();

        mockMvc.perform(get("/api/v1/settings").header("Authorization", "Bearer " + reviewerToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/audit-logs").header("Authorization", "Bearer " + reviewerToken))
            .andExpect(status().isForbidden());
    }

    private JsonNode register(String workspace, String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "workspaceName", workspace,
            "fullName", workspace + " Owner",
            "email", email,
            "password", password
        ));
        String resp = mockMvc.perform(post("/api/v1/auth/register")
                .header("X-Forwarded-For", "10.20.30." + Math.abs(email.hashCode() % 200))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }
}
