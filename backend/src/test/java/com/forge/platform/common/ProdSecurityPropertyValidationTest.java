package com.forge.platform.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProdSecurityPropertyValidationTest {

    private static String randomBase64(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return Base64.getEncoder().encodeToString(buf);
    }

    @Test
    void acceptsValidStrongProductionConfiguration() {
        String strongJwt = randomBase64(48);
        String strongAesKey = randomBase64(32);

        assertThatCode(() -> ProductionSecurityValidator.validateProductionSecrets(
            strongJwt, strongAesKey, "Pr0d$StrongDbP@ssword99", false, false
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsDevDefaultsWeakSecretsAndDemoSeedInProduction() {
        String strongJwt = randomBase64(48);
        String strongAesKey = randomBase64(32);

        assertThatThrownBy(() -> ProductionSecurityValidator.validateProductionSecrets(
            "short", strongAesKey, "StrongDbPass123!", false, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("FORGE_JWT_SECRET");

        assertThatThrownBy(() -> ProductionSecurityValidator.validateProductionSecrets(
            "forge-dev-super-secret-jwt-signing-key-at-least-64-characters-long!",
            strongAesKey, "StrongDbPass123!", false, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("development");

        assertThatThrownBy(() -> ProductionSecurityValidator.validateProductionSecrets(
            strongJwt, "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=", "StrongDbPass123!", false, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("FORGE_ENCRYPTION_KEY");

        assertThatThrownBy(() -> ProductionSecurityValidator.validateProductionSecrets(
            strongJwt, strongAesKey, "forge_dev_password", false, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("SPRING_DATASOURCE_PASSWORD");

        assertThatThrownBy(() -> ProductionSecurityValidator.validateProductionSecrets(
            strongJwt, strongAesKey, "StrongDbPass123!", true, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("Demo workspace seeding");
    }

    @Test
    void rejectsLocalhostInfrastructureWildcardCorsAndMissingInfraInProduction() {
        String strongJwt = randomBase64(48);
        String strongAesKey = randomBase64(32);

        // Localhost DB URL rejected
        assertThatThrownBy(() -> ProductionSecurityValidator.validateProductionConfig(
            strongJwt, strongAesKey, "jdbc:postgresql://localhost:5432/forge", "forge_prod",
            "Pr0d$StrongDbP@ssword99", "redis.internal", "Pr0d$RedisPass99!", "kafka.internal:9092",
            "https://app.forge.example", false, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("SPRING_DATASOURCE_URL");

        // Embedded H2 rejected
        assertThatThrownBy(() -> ProductionSecurityValidator.validateProductionConfig(
            strongJwt, strongAesKey, "jdbc:h2:mem:forge", "forge_prod",
            "Pr0d$StrongDbP@ssword99", "redis.internal", "Pr0d$RedisPass99!", "kafka.internal:9092",
            "https://app.forge.example", false, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("H2");

        // Localhost Redis rejected
        assertThatThrownBy(() -> ProductionSecurityValidator.validateProductionConfig(
            strongJwt, strongAesKey, "jdbc:postgresql://db.internal:5432/forge", "forge_prod",
            "Pr0d$StrongDbP@ssword99", "localhost", "Pr0d$RedisPass99!", "kafka.internal:9092",
            "https://app.forge.example", false, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("SPRING_DATA_REDIS_HOST");

        // Missing Redis password rejected
        assertThatThrownBy(() -> ProductionSecurityValidator.validateProductionConfig(
            strongJwt, strongAesKey, "jdbc:postgresql://db.internal:5432/forge", "forge_prod",
            "Pr0d$StrongDbP@ssword99", "redis.internal", "", "kafka.internal:9092",
            "https://app.forge.example", false, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("SPRING_DATA_REDIS_PASSWORD");

        // Localhost Kafka rejected
        assertThatThrownBy(() -> ProductionSecurityValidator.validateProductionConfig(
            strongJwt, strongAesKey, "jdbc:postgresql://db.internal:5432/forge", "forge_prod",
            "Pr0d$StrongDbP@ssword99", "redis.internal", "Pr0d$RedisPass99!", "127.0.0.1:9092",
            "https://app.forge.example", false, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("SPRING_KAFKA_BOOTSTRAP_SERVERS");

        // Wildcard CORS rejected
        assertThatThrownBy(() -> ProductionSecurityValidator.validateProductionConfig(
            strongJwt, strongAesKey, "jdbc:postgresql://db.internal:5432/forge", "forge_prod",
            "Pr0d$StrongDbP@ssword99", "redis.internal", "Pr0d$RedisPass99!", "kafka.internal:9092",
            "*", false, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("FORGE_CORS_ORIGINS");
    }

    @Test
    void respectsDefaultLocalProfileWhileEnforcingProdAndStagingProfiles() {
        MockEnvironment defaultLocal = new MockEnvironment();
        defaultLocal.setDefaultProfiles("local");
        var localValidator = new ProductionSecurityValidator(defaultLocal, "", "", "", "", "", "", "", "", "", false);
        assertThatCode(localValidator::validateOnStartup).doesNotThrowAnyException();

        MockEnvironment prodWithLocal = new MockEnvironment();
        prodWithLocal.setActiveProfiles("prod", "local");
        var prodLocalValidator = new ProductionSecurityValidator(prodWithLocal, "", "", "", "", "", "", "", "", "", false);
        assertThatThrownBy(prodLocalValidator::validateOnStartup)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FORGE_JWT_SECRET");

        MockEnvironment staging = new MockEnvironment();
        staging.setActiveProfiles("staging");
        var stagingValidator = new ProductionSecurityValidator(staging, "", "", "", "", "", "", "", "", "", false);
        assertThatThrownBy(stagingValidator::validateOnStartup)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FORGE_JWT_SECRET");
    }
}
