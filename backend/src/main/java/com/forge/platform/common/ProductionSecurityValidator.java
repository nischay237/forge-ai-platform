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
 * Fail-fast startup security and production infrastructure gate.
 * Prevents non-local / production deployments from booting with missing, short, or known-development secrets,
 * insecure database/Redis credentials, missing or localhost infrastructure endpoints, wildcard CORS,
 * or enabled demo workspace seeding.
 */
@Component
public class ProductionSecurityValidator {

    private static final Set<String> FORBIDDEN_DEV_ENCRYPTION_KEYS = Set.of(
        "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY="
    );
    private static final Set<String> FORBIDDEN_DB_PASSWORDS = Set.of(
        "forge_dev_password", "postgres", "password", "admin", "changeme", "secret"
    );

    private final Environment environment;
    private final String jwtSecret;
    private final String encryptionKey;
    private final String datasourceUrl;
    private final String datasourceUsername;
    private final String datasourcePassword;
    private final String redisHost;
    private final String redisPassword;
    private final String kafkaBootstrapServers;
    private final String corsAllowedOrigins;
    private final boolean seedEnabled;

    public ProductionSecurityValidator(
        Environment environment,
        @Value("${forge.security.jwt-secret:}") String jwtSecret,
        @Value("${forge.security.encryption-key:}") String encryptionKey,
        @Value("${spring.datasource.url:}") String datasourceUrl,
        @Value("${spring.datasource.username:}") String datasourceUsername,
        @Value("${spring.datasource.password:}") String datasourcePassword,
        @Value("${spring.data.redis.host:}") String redisHost,
        @Value("${spring.data.redis.password:}") String redisPassword,
        @Value("${spring.kafka.bootstrap-servers:}") String kafkaBootstrapServers,
        @Value("${forge.cors.allowed-origins:}") String corsAllowedOrigins,
        @Value("${forge.seed.enabled:false}") boolean seedEnabled
    ) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.encryptionKey = encryptionKey;
        this.datasourceUrl = datasourceUrl;
        this.datasourceUsername = datasourceUsername;
        this.datasourcePassword = datasourcePassword;
        this.redisHost = redisHost;
        this.redisPassword = redisPassword;
        this.kafkaBootstrapServers = kafkaBootstrapServers;
        this.corsAllowedOrigins = corsAllowedOrigins;
        this.seedEnabled = seedEnabled;
    }

    @PostConstruct
    public void validateOnStartup() {
        boolean isProdExplicit = environment.acceptsProfiles(Profiles.of("prod"));
        boolean isLocalOrTest = environment.acceptsProfiles(Profiles.of("local | test"));
        if (!isProdExplicit && isLocalOrTest) {
            return;
        }
        validateProductionConfig(
            jwtSecret,
            encryptionKey,
            datasourceUrl,
            datasourceUsername,
            datasourcePassword,
            redisHost,
            redisPassword,
            kafkaBootstrapServers,
            corsAllowedOrigins,
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
        validateProductionConfig(
            jwtSecret,
            encryptionKey,
            "jdbc:postgresql://postgres.internal:5432/forge",
            "forge_prod",
            datasourcePassword,
            "redis.internal",
            "Pr0d$RedisPass99!",
            "kafka.internal:9092",
            "https://app.forge.example",
            seedEnabled,
            demoProfileActive
        );
    }

    public static void validateProductionConfig(
        String jwtSecret,
        String encryptionKey,
        String datasourceUrl,
        String datasourceUsername,
        String datasourcePassword,
        String redisHost,
        String redisPassword,
        String kafkaBootstrapServers,
        String corsAllowedOrigins,
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

        if (datasourceUrl == null || datasourceUrl.isBlank()) {
            violations.add("SPRING_DATASOURCE_URL is missing");
        } else {
            String lowerUrl = datasourceUrl.trim().toLowerCase(Locale.ROOT);
            if (lowerUrl.startsWith("jdbc:h2:")) {
                violations.add("SPRING_DATASOURCE_URL must not use embedded H2 in production");
            }
            if (containsLocalhostTarget(lowerUrl)) {
                violations.add("SPRING_DATASOURCE_URL must not target localhost/127.0.0.1 in production");
            }
        }

        if (datasourceUsername == null || datasourceUsername.isBlank()) {
            violations.add("SPRING_DATASOURCE_USERNAME is missing");
        }

        if (datasourcePassword == null || datasourcePassword.isBlank()) {
            violations.add("SPRING_DATASOURCE_PASSWORD is missing");
        } else if (FORBIDDEN_DB_PASSWORDS.contains(datasourcePassword.trim().toLowerCase(Locale.ROOT))) {
            violations.add("SPRING_DATASOURCE_PASSWORD must not use a default development password");
        }

        if (redisHost == null || redisHost.isBlank()) {
            violations.add("SPRING_DATA_REDIS_HOST is missing");
        } else if (containsLocalhostTarget(redisHost.trim().toLowerCase(Locale.ROOT))) {
            violations.add("SPRING_DATA_REDIS_HOST must not target localhost/127.0.0.1 in production");
        }

        if (redisPassword == null || redisPassword.isBlank()) {
            violations.add("SPRING_DATA_REDIS_PASSWORD is missing");
        } else if (FORBIDDEN_DB_PASSWORDS.contains(redisPassword.trim().toLowerCase(Locale.ROOT))) {
            violations.add("SPRING_DATA_REDIS_PASSWORD must not use a default weak password");
        }

        if (kafkaBootstrapServers == null || kafkaBootstrapServers.isBlank()) {
            violations.add("SPRING_KAFKA_BOOTSTRAP_SERVERS is missing");
        } else if (containsLocalhostTarget(kafkaBootstrapServers.trim().toLowerCase(Locale.ROOT))) {
            violations.add("SPRING_KAFKA_BOOTSTRAP_SERVERS must not target localhost/127.0.0.1 in production");
        }

        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
            violations.add("FORGE_CORS_ORIGINS is missing");
        } else {
            for (String origin : corsAllowedOrigins.split(",")) {
                String trimmed = origin.trim();
                if ("*".equals(trimmed)) {
                    violations.add("FORGE_CORS_ORIGINS must not use wildcard '*' with allowCredentials=true");
                    break;
                }
            }
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

    private static boolean containsLocalhostTarget(String value) {
        return value.contains("localhost") || value.contains("127.0.0.1") || value.contains("::1");
    }
}
