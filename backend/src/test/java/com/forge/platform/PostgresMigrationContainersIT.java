package com.forge.platform;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL 16 Flyway migration verification suite.
 * Runs automatically on Linux CI runners and Docker-enabled hosts; cleanly skipped when Docker daemon is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationContainersIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("forge_it")
        .withUsername("forge")
        .withPassword("TestContainerStrong#2026");

    @Test
    void appliesAllFlywayMigrationsCleanlyOnRealPostgres16() throws Exception {
        Flyway flyway = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load();

        var result = flyway.migrate();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(6);

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             ResultSet rs = conn.createStatement().executeQuery(
                 "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ("
                     + "'refresh_tokens', 'change_risk_policies', 'finding_suppressions', "
                     + "'prediction_runs', 'prediction_items', 'prediction_evidence', "
                     + "'deployment_records', 'incident_records', 'defect_records', 'historical_test_executions')")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(10);
        }
    }
}
