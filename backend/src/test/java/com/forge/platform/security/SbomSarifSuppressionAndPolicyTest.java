package com.forge.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class SbomSarifSuppressionAndPolicyTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void runsScaScanExportsCycloneDxSbomSarifSuppressesFindingAndUpdatesPolicy() throws Exception {
        String suf = UUID.randomUUID().toString().substring(0, 6);
        String regResp = mockMvc.perform(post("/api/v1/auth/register")
                .header("X-Forwarded-For", "10.99.1." + Math.abs(suf.hashCode() % 200))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "workspaceName", "SecOps " + suf,
                    "fullName", "Sec Owner",
                    "email", "sec-" + suf + "@forge.test",
                    "password", "StrongPass#2026"
                ))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(regResp).path("accessToken").asText();

        String pomXml = """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.apache.logging.log4j</groupId>
                  <artifactId>log4j-core</artifactId>
                  <version>2.14.1</version>
                </dependency>
              </dependencies>
            </project>
            """;
        String pkgJson = """
            {
              "name": "edge-app",
              "dependencies": {
                "lodash": "^4.17.19",
                "react": "^18.3.1"
              }
            }
            """;

        String repoResp = mockMvc.perform(post("/api/v1/repositories")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "owner", "sec-org",
                    "name", "vulnerable-svc-" + suf,
                    "defaultBranch", "main",
                    "primaryLanguage", "Java",
                    "fetchFromGithub", false,
                    "initialFiles", Map.of("pom.xml", pomXml, "frontend/package.json", pkgJson)
                ))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String repoId = objectMapper.readTree(repoResp).path("id").asText();

        // 1. Run full repository security scan (detects Log4Shell & lodash CVEs via SCA!)
        String scanResp = mockMvc.perform(post("/api/v1/security/repositories/" + repoId + "/scan")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode findings = objectMapper.readTree(scanResp);
        assertThat(findings.size()).isGreaterThanOrEqualTo(2);
        String firstFp = findings.get(0).path("fingerprint").asText();
        String firstRule = findings.get(0).path("ruleId").asText();
        assertThat(firstFp).hasSize(64);

        // 2. Export CycloneDX 1.5 SBOM
        String sbomResp = mockMvc.perform(get("/api/v1/security/repositories/" + repoId + "/sbom")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bomFormat").value("CycloneDX"))
            .andExpect(jsonPath("$.specVersion").value("1.5"))
            .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(sbomResp).path("components").size()).isEqualTo(3);

        // 3. Create suppression for first finding -> verify SARIF 2.1.0 includes suppression & schema
        mockMvc.perform(post("/api/v1/security/suppressions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "repositoryId", repoId,
                    "fingerprint", firstFp,
                    "ruleId", firstRule,
                    "reason", "Mitigated by WAF and upgrade PR #912 scheduled this sprint"
                ))))
            .andExpect(status().isCreated());

        String sarifResp = mockMvc.perform(get("/api/v1/security/repositories/" + repoId + "/sarif")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value("2.1.0"))
            .andReturn().getResponse().getContentAsString();
        JsonNode sarifResults = objectMapper.readTree(sarifResp).path("runs").get(0).path("results");
        assertThat(sarifResults.size()).isGreaterThanOrEqualTo(2);
        assertThat(sarifResp).contains("suppressions");

        // 4. Policy-as-Code update bumps version and persists thresholds
        mockMvc.perform(put("/api/v1/policies")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "maxRiskScoreForPass", 20,
                    "maxRiskScoreForWarn", 60,
                    "blockOnBreakingApi", true
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.maxRiskScoreForPass").value(20));

        // 5. Architecture Drift endpoint returns structured report
        mockMvc.perform(get("/api/v1/repositories/" + repoId + "/code-intel/drift")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.repositoryId").value(repoId));
    }
}
