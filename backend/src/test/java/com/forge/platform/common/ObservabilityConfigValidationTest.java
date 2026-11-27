package com.forge.platform.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that provisioned Grafana dashboards, Prometheus alert rules, and Docker Compose
 * monitoring mounts remain syntactically valid and aligned with real FORGE Micrometer metrics.
 */
class ObservabilityConfigValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Yaml yaml = new Yaml();

    private Path resolveRepoRoot() {
        Path direct = Path.of("infra");
        if (Files.isDirectory(direct)) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        Path parent = Path.of("..", "infra");
        if (Files.isDirectory(parent)) {
            return Path.of("..").toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Could not locate repository root containing infra/");
    }

    @Test
    void validatesGrafanaDashboardJsonAndPromQlMetrics() throws Exception {
        Path root = resolveRepoRoot();
        Path dashboardPath = root.resolve("infra/grafana/dashboards/forge-control-plane.json");
        assertThat(dashboardPath).exists();

        JsonNode dashboard = objectMapper.readTree(Files.readString(dashboardPath));
        assertThat(dashboard.path("uid").asText()).isEqualTo("forge-control-plane");
        JsonNode panels = dashboard.path("panels");
        assertThat(panels.isArray()).isTrue();
        assertThat(panels.size()).isGreaterThanOrEqualTo(8);

        List<String> expressions = new ArrayList<>();
        for (JsonNode panel : panels) {
            assertThat(panel.path("title").asText()).isNotBlank();
            for (JsonNode target : panel.path("targets")) {
                expressions.add(target.path("expr").asText());
            }
        }

        String joined = String.join("\n", expressions);
        assertThat(joined).contains(
            "forge_outbox_pending",
            "forge_outbox_dead_letter",
            "forge_outbox_oldest_pending_age_seconds",
            "forge_sse_connections_active",
            "forge_reviews_completed_total",
            "forge_predictions_completed_total",
            "forge_kafka_dlt_events_total",
            "forge_redis_errors_total",
            "hikaricp_connections_active"
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void validatesPrometheusAlertsAndDockerComposeMounts() throws Exception {
        Path root = resolveRepoRoot();
        Path alertsPath = root.resolve("infra/prometheus/alerts.yml");
        Path promPath = root.resolve("infra/prometheus/prometheus.yml");
        Path grafanaProvPath = root.resolve("infra/grafana/provisioning/dashboards/dashboards.yml");
        Path composePath = root.resolve("docker-compose.yml");

        Map<String, Object> alertsDoc = yaml.load(Files.readString(alertsPath));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) alertsDoc.get("groups");
        assertThat(groups).isNotEmpty();
        List<Map<String, Object>> rules = (List<Map<String, Object>>) groups.get(0).get("rules");
        assertThat(rules).hasSizeGreaterThanOrEqualTo(7);
        for (Map<String, Object> rule : rules) {
            assertThat(String.valueOf(rule.get("alert"))).startsWith("Forge");
            assertThat(String.valueOf(rule.get("expr"))).isNotBlank();
        }

        Map<String, Object> promDoc = yaml.load(Files.readString(promPath));
        List<String> ruleFiles = (List<String>) promDoc.get("rule_files");
        assertThat(ruleFiles).contains("/etc/prometheus/alerts.yml");

        Map<String, Object> provDoc = yaml.load(Files.readString(grafanaProvPath));
        assertThat((List<?>) provDoc.get("providers")).isNotEmpty();

        String composeContent = Files.readString(composePath);
        assertThat(composeContent).contains("./infra/prometheus/alerts.yml:/etc/prometheus/alerts.yml:ro");
        assertThat(composeContent).contains("./infra/grafana/dashboards:/var/lib/grafana/dashboards:ro");
    }
}
