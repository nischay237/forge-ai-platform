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
    void respectsDefaultLocalProfileWhileEnforcingProdAndStagingProfiles() {
        MockEnvironment defaultLocal = new MockEnvironment();
        defaultLocal.setDefaultProfiles("local");
        var localValidator = new ProductionSecurityValidator(defaultLocal, "", "", "", false);
        assertThatCode(localValidator::validateOnStartup).doesNotThrowAnyException();

        MockEnvironment prodWithLocal = new MockEnvironment();
        prodWithLocal.setActiveProfiles("prod", "local");
        var prodLocalValidator = new ProductionSecurityValidator(prodWithLocal, "", "", "", false);
        assertThatThrownBy(prodLocalValidator::validateOnStartup)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FORGE_JWT_SECRET");

        MockEnvironment staging = new MockEnvironment();
        staging.setActiveProfiles("staging");
        var stagingValidator = new ProductionSecurityValidator(staging, "", "", "", false);
        assertThatThrownBy(stagingValidator::validateOnStartup)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FORGE_JWT_SECRET");
    }
}
