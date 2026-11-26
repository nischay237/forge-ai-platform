package com.forge.platform.common;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-fast startup security gate.
 * Prevents non-local / production deployments from booting with missing, short, or known-development secrets,
 * insecure database credentials, or enabled demo workspace seeding.
 */
@Component
public class ProductionSecurityValidator {

    private static final Set<String> FORBIDDEN_DEV_ENCRYPTION_KEYS = Set.of(
        "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY="
    );
    private static final Set<String> FORBIDDEN_DB_PASSWORDS = Set.of(
        "forge_dev_password", "postgres", "password", "admin", "changeme"
    );

    private final Environment environment;
    private final String jwtSecret;
    private final String encryptionKey;
    private final String datasourcePassword;
    private final boolean seedEnabled;

    public ProductionSecurityValidator(
        Environment environment,
        @Value("${forge.security.jwt-secret:}") String jwtSecret,
        @Value("${forge.security.encryption-key:}") String encryptionKey,
        @Value("${spring.datasource.password:}") String datasourcePassword,
        @Value("${forge.seed.enabled:false}") boolean seedEnabled
    ) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.encryptionKey = encryptionKey;
        this.datasourcePassword = datasourcePassword;
        this.seedEnabled = seedEnabled;
    }

    @PostConstruct
    public void validateOnStartup() {
        boolean isProdExplicit = environment.acceptsProfiles(Profiles.of("prod"));
        boolean isLocalOrTest = environment.acceptsProfiles(Profiles.of("local | test"));
        if (!isProdExplicit && isLocalOrTest) {
            return;
        }
        validateProductionSecrets(
            jwtSecret,
            encryptionKey,
            datasourcePassword,
            seedEnabled,
            environment.acceptsProfiles(Profiles.of("demo"))
        );
    }

    public static void validateProductionSecrets(
        String jwtSecret,
        String encryptionKey,
        String datasourcePassword,
        boolean seedEnabled,
        boolean demoProfileActive
    ) {
        List<String> violations = new ArrayList<>();

        if (jwtSecret == null || jwtSecret.isBlank()) {
            violations.add("FORGE_JWT_SECRET is missing");
        } else {
            String lower = jwtSecret.toLowerCase(Locale.ROOT);
            if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                violations.add("FORGE_JWT_SECRET must be at least 32 bytes long");
            }
            if (lower.contains("forge-dev") || lower.contains("local-dev") || lower.contains("change-me") || lower.contains("replace_with")) {
                violations.add("FORGE_JWT_SECRET must not use a development or placeholder value");
            }
        }

        if (encryptionKey == null || encryptionKey.isBlank()) {
            violations.add("FORGE_ENCRYPTION_KEY is missing");
        } else if (FORBIDDEN_DEV_ENCRYPTION_KEYS.contains(encryptionKey.trim())) {
            violations.add("FORGE_ENCRYPTION_KEY must not use the known development default key");
        } else {
            try {
                byte[] decoded = Base64.getDecoder().decode(encryptionKey.trim());
                if (decoded.length != 32) {
                    violations.add("FORGE_ENCRYPTION_KEY must be a Base64-encoded 32-byte (256-bit) AES key");
                }
            } catch (IllegalArgumentException ex) {
                violations.add("FORGE_ENCRYPTION_KEY must be valid Base64 encoding a 32-byte AES key");
            }
        }

        if (datasourcePassword == null || datasourcePassword.isBlank()) {
            violations.add("SPRING_DATASOURCE_PASSWORD is missing");
        } else if (FORBIDDEN_DB_PASSWORDS.contains(datasourcePassword.trim().toLowerCase(Locale.ROOT))) {
            violations.add("SPRING_DATASOURCE_PASSWORD must not use a default development password");
        }

        if (seedEnabled || demoProfileActive) {
            violations.add("Demo workspace seeding (forge.seed.enabled / demo profile) is forbidden in production");
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                "FORGE production security validation failed — refusing to start: " + String.join("; ", violations)
            );
        }
    }
}
