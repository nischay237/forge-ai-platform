package com.forge.platform.security;

import com.forge.platform.repository.RepositoryEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates OASIS SARIF 2.1.0 JSON reports compatible with GitHub Code Scanning,
 * VS Code SARIF Viewer, and enterprise AppSec aggregation pipelines.
 */
@Service
public class SarifExportService {

    public Map<String, Object> buildSarifReport(
        RepositoryEntity repo,
        List<SecurityFindingEntity> findings
    ) {
        Map<String, Map<String, Object>> rulesById = new LinkedHashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();

        for (SecurityFindingEntity f : findings) {
            rulesById.putIfAbsent(f.getRuleId(), buildRuleDescriptor(f));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ruleId", f.getRuleId());
            result.put("level", mapSarifLevel(f.getSeverity()));
            result.put("message", Map.of(
                "text", f.getTitle() + " — " + f.getRemediation()
            ));
            result.put("locations", List.of(Map.of(
                "physicalLocation", Map.of(
                    "artifactLocation", Map.of("uri", f.getFilePath()),
                    "region", Map.of(
                        "startLine", Math.max(1, f.getLineNumber()),
                        "snippet", Map.of("text", f.getMaskedEvidence() != null ? f.getMaskedEvidence() : "")
                    )
                )
            )));
            if (f.getFingerprint() != null && !f.getFingerprint().isBlank()) {
                result.put("partialFingerprints", Map.of("primaryLocationLineHash", f.getFingerprint()));
            }
            if ("SUPPRESSED".equalsIgnoreCase(f.getStatus()) || "FALSE_POSITIVE".equalsIgnoreCase(f.getStatus())) {
                result.put("suppressions", List.of(Map.of(
                    "kind", "external",
                    "status", "accepted",
                    "justification", "Triaged as " + f.getStatus() + " in FORGE Security Center"
                )));
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("forgeSeverity", f.getSeverity());
            props.put("forgeStatus", f.getStatus());
            props.put("scanner", f.getScanner());
            if (f.getCweId() != null) props.put("cwe", f.getCweId());
            result.put("properties", props);

            results.add(result);
        }

        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "FORGE Security Intelligence Engine");
        driver.put("version", "2.0.0");
        driver.put("informationUri", "https://github.com/nischay237/forge-ai-platform");
        driver.put("rules", new ArrayList<>(rulesById.values()));

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", driver));
        run.put("results", results);
        if (repo != null) {
            run.put("properties", Map.of("repository", repo.getFullName()));
        }

        Map<String, Object> sarif = new LinkedHashMap<>();
        sarif.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        sarif.put("version", "2.1.0");
        sarif.put("runs", List.of(run));
        return sarif;
    }

    private Map<String, Object> buildRuleDescriptor(SecurityFindingEntity f) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", f.getRuleId());
        rule.put("shortDescription", Map.of("text", f.getTitle()));
        rule.put("help", Map.of("text", f.getRemediation()));
        rule.put("defaultConfiguration", Map.of("level", mapSarifLevel(f.getSeverity())));
        if (f.getCweId() != null && !f.getCweId().isBlank()) {
            rule.put("properties", Map.of("tags", List.of("security", f.getCweId(), f.getScanner())));
        }
        return rule;
    }

    private String mapSarifLevel(String severity) {
        if ("CRITICAL".equalsIgnoreCase(severity) || "HIGH".equalsIgnoreCase(severity)) {
            return "error";
        }
        if ("MEDIUM".equalsIgnoreCase(severity)) {
            return "warning";
        }
        return "note";
    }
}
