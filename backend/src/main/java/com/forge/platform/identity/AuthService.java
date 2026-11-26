package com.forge.platform.identity;

import com.forge.platform.audit.AuditService;
import com.forge.platform.common.ForgeException;
import com.forge.platform.common.RateLimitExceededException;
import com.forge.platform.common.ResourceNotFoundException;
import com.forge.platform.common.SecretCryptoService;
import com.forge.platform.common.SecretDecryptionException;
import com.forge.platform.common.TenantContext;
import com.forge.platform.messaging.RateLimiterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {

    private static final Set<String> VALID_AI_PROVIDERS = Set.of("HYBRID", "OPENAI", "DETERMINISTIC");

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenRevocationService tokenRevocationService;
    private final AuthLockoutService authLockoutService;
    private final RateLimiterService rateLimiterService;
    private final SecretCryptoService cryptoService;
    private final IdentityMapper identityMapper;
    private final AuditService auditService;
    private final Environment environment;
    private final String openAiApiKey;
    private final long refreshExpirationMs;
    private final String dummyBcryptHash;

    public AuthService(
        TenantRepository tenantRepository,
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        TokenRevocationService tokenRevocationService,
        AuthLockoutService authLockoutService,
        RateLimiterService rateLimiterService,
        SecretCryptoService cryptoService,
        IdentityMapper identityMapper,
        AuditService auditService,
        Environment environment,
        @Value("${forge.ai.openai-api-key:}") String openAiApiKey,
        @Value("${forge.security.refresh-expiration-ms:604800000}") long refreshExpirationMs
    ) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenRevocationService = tokenRevocationService;
        this.authLockoutService = authLockoutService;
        this.rateLimiterService = rateLimiterService;
        this.cryptoService = cryptoService;
        this.identityMapper = identityMapper;
        this.auditService = auditService;
        this.environment = environment;
        this.openAiApiKey = openAiApiKey;
        this.refreshExpirationMs = refreshExpirationMs;
        this.dummyBcryptHash = passwordEncoder.encode("forge-timing-equalization-dummy-secret");
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request, String clientIp) {
        String safeIp = sanitizeIp(clientIp);
        if (!rateLimiterService.allowRequest("auth-register-ip:" + safeIp, 10, Duration.ofMinutes(15))) {
            throw new RateLimitExceededException("Too many workspace registration attempts. Please try again later.");
        }

        if (userRepository.existsByEmailIgnoreCase(request.email().trim())) {
            throw new ForgeException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "An account with this email already exists");
        }

        String baseSlug = request.workspaceName().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        String slug = baseSlug.isBlank() ? "workspace" : baseSlug;
        if (tenantRepository.findBySlug(slug).isPresent()) {
            slug = slug + "-" + cryptoService.generateSecureToken(4).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        }

        // Generate a unique cryptographically secure per-tenant GitHub webhook secret
        String uniqueWebhookSecret = "whsec_" + cryptoService.generateSecureToken(32);
        Tenant tenant = Tenant.create(request.workspaceName().trim(), slug, null);
        tenant.setWebhookSecretEncrypted(cryptoService.encrypt(uniqueWebhookSecret, webhookAad(tenant.getId())));
        tenantRepository.save(tenant);

        User user = User.create(
            tenant.getId(),
            request.email(),
            request.fullName(),
            passwordEncoder.encode(request.password()),
            Role.OWNER
        );
        userRepository.save(user);

        auditService.record(tenant.getId(), user.getEmail(), user.getRole().name(),
            "WORKSPACE_REGISTERED", "TENANT", tenant.getId(),
            "Created workspace '" + tenant.getName() + "' with unique per-tenant webhook secret and OWNER account", safeIp);

        return issueSessionTokens(user, tenant, null, uniqueWebhookSecret);
    }

    @Transactional
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request, String clientIp) {
        String safeIp = sanitizeIp(clientIp);
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        if (!rateLimiterService.allowRequest("auth-login-ip:" + safeIp, 25, Duration.ofMinutes(15))
            || !rateLimiterService.allowRequest("auth-login-email:" + cryptoService.sha256Hex(normalizedEmail), 12, Duration.ofMinutes(15))) {
            throw new RateLimitExceededException("Too many login attempts. Please wait before trying again.");
        }

        User user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        if (user == null) {
            // Equalize BCrypt work factor timing to reduce email enumeration oracle
            passwordEncoder.matches(request.password(), dummyBcryptHash);
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }

        if (!user.isActive()) {
            auditService.recordIsolated(user.getTenantId(), user.getEmail(), user.getRole().name(),
                "USER_LOGIN_BLOCKED_INACTIVE", "USER", user.getId(),
                "Rejected login for deactivated account", safeIp);
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }

        if (user.isLocked()) {
            auditService.recordIsolated(user.getTenantId(), user.getEmail(), user.getRole().name(),
                "USER_LOGIN_LOCKED", "USER", user.getId(),
                "Rejected login while account lockout window is active", safeIp);
            throw new ForgeException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                "Account temporarily locked due to repeated failed login attempts. Try again later.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            boolean nowLocked = authLockoutService.recordFailure(user.getId());
            auditService.recordIsolated(user.getTenantId(), user.getEmail(), user.getRole().name(),
                nowLocked ? "USER_ACCOUNT_LOCKED" : "USER_LOGIN_FAILED", "USER", user.getId(),
                nowLocked ? "Account locked after repeated failed login attempts" : "Invalid password supplied",
                safeIp);
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }

        authLockoutService.resetFailures(user.getId());

        Tenant tenant = tenantRepository.findById(user.getTenantId())
            .orElseThrow(() -> new ResourceNotFoundException("Workspace not found"));

        auditService.record(tenant.getId(), user.getEmail(), user.getRole().name(),
            "USER_LOGIN", "USER", user.getId(), "Authenticated via JWT with rotating refresh session", safeIp);

        return issueSessionTokens(user, tenant, null, null);
    }

    @Transactional
    public AuthDtos.AuthResponse refreshSession(AuthDtos.RefreshRequest request, String clientIp) {
        String safeIp = sanitizeIp(clientIp);
        if (!rateLimiterService.allowRequest("auth-refresh-ip:" + safeIp, 40, Duration.ofMinutes(15))) {
            throw new RateLimitExceededException("Too many token refresh requests.");
        }

        String presentedHash = cryptoService.sha256Hex(request.refreshToken().trim());
        RefreshTokenEntity existing = refreshTokenRepository.findByTokenHash(presentedHash)
            .orElseThrow(() -> new ForgeException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Invalid or expired refresh token"));

        if (existing.isRevoked()) {
            // Replay / reuse detected: revoke the entire token family in REQUIRES_NEW so throwing 401 never rolls it back!
            authLockoutService.revokeRefreshFamily(existing.getFamilyId());
            auditService.recordIsolated(existing.getTenantId(), "security@forge.dev", "SYSTEM",
                "REFRESH_TOKEN_REUSE_DETECTED", "USER", existing.getUserId(),
                "Detected replayed refresh token; revoked entire token family " + existing.getFamilyId(), safeIp);
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSE_DETECTED",
                "Session invalidated due to refresh token reuse detection. Please sign in again.");
        }

        if (existing.isExpired()) {
            existing.revoke(null);
            refreshTokenRepository.save(existing);
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED", "Refresh token has expired. Please sign in again.");
        }

        User user = userRepository.findByIdAndTenantId(existing.getUserId(), existing.getTenantId())
            .orElseThrow(() -> new ForgeException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "User account no longer exists"));
        if (!user.isActive() || user.isLocked()) {
            existing.revoke(null);
            refreshTokenRepository.save(existing);
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", "Account is inactive or locked");
        }

        Tenant tenant = tenantRepository.findById(user.getTenantId())
            .orElseThrow(() -> new ResourceNotFoundException("Workspace not found"));

        String nextRawRefresh = "frt_" + cryptoService.generateSecureToken(40);
        String nextHash = cryptoService.sha256Hex(nextRawRefresh);
        existing.revoke(nextHash);
        refreshTokenRepository.save(existing);

        RefreshTokenEntity rotated = RefreshTokenEntity.issue(
            tenant.getId(),
            user.getId(),
            existing.getFamilyId(),
            nextHash,
            Instant.now().plusMillis(refreshExpirationMs)
        );
        refreshTokenRepository.save(rotated);

        String accessToken = jwtService.generateToken(user);
        return new AuthDtos.AuthResponse(
            accessToken,
            nextRawRefresh,
            "Bearer",
            Math.max(1L, jwtService.getExpirationMs() / 1000L),
            identityMapper.toUserDto(user),
            toTenantDto(tenant),
            null
        );
    }

    @Transactional
    public void logout(AuthDtos.LogoutRequest request, String clientIp) {
        TenantContext.PrincipalContext principal = TenantContext.get();
        if (principal == null) {
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }

        if (principal.tokenJti() != null && !principal.tokenJti().isBlank()) {
            tokenRevocationService.revokeJti(principal.tokenJti(), Duration.ofMillis(jwtService.getExpirationMs()));
        }

        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            String hash = cryptoService.sha256Hex(request.refreshToken().trim());
            refreshTokenRepository.findByTokenHash(hash).ifPresent(rt -> {
                if (rt.getTenantId().equals(principal.tenantId()) && rt.getUserId().equals(principal.userId())) {
                    List<RefreshTokenEntity> family = refreshTokenRepository.findByFamilyIdAndRevokedAtIsNull(rt.getFamilyId());
                    for (RefreshTokenEntity token : family) {
                        token.revoke(null);
                    }
                    refreshTokenRepository.saveAll(family);
                }
            });
        } else {
            List<RefreshTokenEntity> activeTokens = refreshTokenRepository
                .findByTenantIdAndUserIdAndRevokedAtIsNull(principal.tenantId(), principal.userId());
            for (RefreshTokenEntity token : activeTokens) {
                token.revoke(null);
            }
            refreshTokenRepository.saveAll(activeTokens);
        }

        auditService.record(principal.tenantId(), principal.email(), principal.role(),
            "USER_LOGOUT", "USER", principal.userId(),
            "Revoked active JWT and refresh token session", sanitizeIp(clientIp));
    }

    /**
     * Read-only session verification endpoint. Never mints a new JWT.
     */
    @Transactional(readOnly = true)
    public AuthDtos.SessionResponse currentSession() {
        TenantContext.PrincipalContext principal = TenantContext.get();
        if (principal == null) {
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        User user = userRepository.findByIdAndTenantId(principal.userId(), principal.tenantId())
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!user.isActive() || user.isLocked()) {
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", "Account is inactive or locked");
        }
        Tenant tenant = tenantRepository.findById(principal.tenantId())
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
        return new AuthDtos.SessionResponse(identityMapper.toUserDto(user), toTenantDto(tenant));
    }

    @Transactional(readOnly = true)
    public AuthDtos.WorkspaceSettingsResponse getWorkspaceSettings() {
        String tenantId = TenantContext.requireTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Workspace not found"));
        List<AuthDtos.UserDto> members = userRepository.findByTenantIdOrderByCreatedAtAsc(tenantId)
            .stream()
            .map(identityMapper::toUserDto)
            .toList();

        String profile = environment.getActiveProfiles().length > 0
            ? String.join(",", Arrays.asList(environment.getActiveProfiles()))
            : "local";

        return new AuthDtos.WorkspaceSettingsResponse(
            toTenantDto(tenant),
            members,
            profile,
            openAiApiKey != null && !openAiApiKey.isBlank() && !openAiApiKey.startsWith("disabled-")
        );
    }

    @Transactional
    public AuthDtos.TenantDto updateWorkspaceSettings(AuthDtos.UpdateSettingsRequest request) {
        String tenantId = TenantContext.requireTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Workspace not found"));

        if (request.workspaceName() != null && !request.workspaceName().isBlank()) {
            tenant.setName(request.workspaceName().trim());
        }
        if (request.aiProvider() != null && !request.aiProvider().isBlank()) {
            String provider = request.aiProvider().trim().toUpperCase(Locale.ROOT);
            if (!VALID_AI_PROVIDERS.contains(provider)) {
                throw new ForgeException(HttpStatus.BAD_REQUEST, "INVALID_AI_PROVIDER",
                    "AI provider must be one of HYBRID, OPENAI, or DETERMINISTIC");
            }
            tenant.setAiProvider(provider);
        }
        if (request.promptVersion() != null && !request.promptVersion().isBlank()) {
            tenant.setPromptVersion(request.promptVersion().trim());
        }
        if (request.requireApprovalBeforePublish() != null) {
            tenant.setRequireApprovalBeforePublish(request.requireApprovalBeforePublish());
        }
        if (request.newWebhookSecret() != null && !request.newWebhookSecret().isBlank()) {
            String secret = request.newWebhookSecret().trim();
            if (secret.length() < 16) {
                throw new ForgeException(HttpStatus.BAD_REQUEST, "WEAK_WEBHOOK_SECRET",
                    "Webhook secret must be at least 16 characters long");
            }
            tenant.setWebhookSecretEncrypted(cryptoService.encrypt(secret, webhookAad(tenant.getId())));
        }
        if (request.newGithubToken() != null) {
            String trimmedToken = request.newGithubToken().trim();
            if (trimmedToken.isBlank()) {
                tenant.setGithubTokenEncrypted(null);
            } else {
                tenant.setGithubTokenEncrypted(cryptoService.encrypt(trimmedToken, githubTokenAad(tenant.getId())));
            }
        }
        tenantRepository.save(tenant);

        auditService.recordCurrent("SETTINGS_UPDATED", "TENANT", tenant.getId(),
            "Updated workspace AI/security settings (provider=" + tenant.getAiProvider()
                + ", prompt=" + tenant.getPromptVersion()
                + ", approvalGuard=" + tenant.isRequireApprovalBeforePublish()
                + ", githubConnected=" + (!resolveTenantGithubToken(tenant).isBlank()) + ")");

        return toTenantDto(tenant);
    }

    @Transactional
    public AuthDtos.WebhookSecretRotationResponse rotateWebhookSecret() {
        String tenantId = TenantContext.requireTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Workspace not found"));

        String freshSecret = "whsec_" + cryptoService.generateSecureToken(32);
        tenant.setWebhookSecretEncrypted(cryptoService.encrypt(freshSecret, webhookAad(tenant.getId())));
        tenantRepository.save(tenant);

        auditService.recordCurrent("WEBHOOK_SECRET_ROTATED", "TENANT", tenant.getId(),
            "Generated and stored new per-tenant HMAC webhook secret");

        return new AuthDtos.WebhookSecretRotationResponse(freshSecret, toTenantDto(tenant));
    }

    /**
     * Resolves the tenant's per-workspace webhook secret. Fails closed if missing or corrupted.
     * Never falls back to any global shared secret.
     */
    public String resolveTenantWebhookSecret(Tenant tenant) {
        if (tenant.getWebhookSecretEncrypted() == null || tenant.getWebhookSecretEncrypted().isBlank()) {
            throw new ForgeException(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_SECRET_NOT_CONFIGURED",
                "Workspace webhook secret is not configured. Rotate the webhook secret in Settings.");
        }
        try {
            String decrypted = cryptoService.decrypt(tenant.getWebhookSecretEncrypted(), webhookAad(tenant.getId()));
            if (decrypted == null || decrypted.isBlank()) {
                throw new SecretDecryptionException("Empty decrypted webhook secret");
            }
            return decrypted;
        } catch (SecretDecryptionException ex) {
            throw new ForgeException(HttpStatus.INTERNAL_SERVER_ERROR, "CREDENTIAL_DECRYPTION_FAILED",
                "Failed to decrypt workspace webhook secret; rotate secret in Settings.");
        }
    }

    /**
     * Strictly tenant-scoped GitHub PAT resolution. Never falls back to a global server token
     * so one tenant can never access repositories or post comments using another tenant's credentials.
     */
    public String resolveTenantGithubToken(Tenant tenant) {
        if (tenant.getGithubTokenEncrypted() == null || tenant.getGithubTokenEncrypted().isBlank()) {
            return "";
        }
        try {
            String decrypted = cryptoService.decrypt(tenant.getGithubTokenEncrypted(), githubTokenAad(tenant.getId()));
            return decrypted != null ? decrypted.trim() : "";
        } catch (SecretDecryptionException ex) {
            throw new ForgeException(HttpStatus.INTERNAL_SERVER_ERROR, "CREDENTIAL_DECRYPTION_FAILED",
                "Failed to decrypt workspace GitHub token; please re-save or clear it in Settings.");
        }
    }

    public static String webhookAad(String tenantId) {
        return tenantId + ":webhook_secret";
    }

    public static String githubTokenAad(String tenantId) {
        return tenantId + ":github_token";
    }

    private AuthDtos.AuthResponse issueSessionTokens(
        User user, Tenant tenant, String existingFamilyId, String initialWebhookSecret
    ) {
        String accessToken = jwtService.generateToken(user);
        String rawRefreshToken = "frt_" + cryptoService.generateSecureToken(40);
        String familyId = existingFamilyId != null ? existingFamilyId : UUID.randomUUID().toString();
        RefreshTokenEntity rt = RefreshTokenEntity.issue(
            tenant.getId(),
            user.getId(),
            familyId,
            cryptoService.sha256Hex(rawRefreshToken),
            Instant.now().plusMillis(refreshExpirationMs)
        );
        refreshTokenRepository.save(rt);

        return new AuthDtos.AuthResponse(
            accessToken,
            rawRefreshToken,
            "Bearer",
            Math.max(1L, jwtService.getExpirationMs() / 1000L),
            identityMapper.toUserDto(user),
            toTenantDto(tenant),
            initialWebhookSecret
        );
    }

    private AuthDtos.TenantDto toTenantDto(Tenant tenant) {
        String maskedWebhook = "****";
        if (tenant.getWebhookSecretEncrypted() != null && !tenant.getWebhookSecretEncrypted().isBlank()) {
            try {
                maskedWebhook = cryptoService.maskSecret(resolveTenantWebhookSecret(tenant));
            } catch (ForgeException ex) {
                maskedWebhook = "ROTATION_REQUIRED";
            }
        }
        String maskedGh = "";
        boolean configured = false;
        if (tenant.getGithubTokenEncrypted() != null && !tenant.getGithubTokenEncrypted().isBlank()) {
            try {
                String githubToken = resolveTenantGithubToken(tenant);
                configured = !githubToken.isBlank();
                maskedGh = configured ? cryptoService.maskSecret(githubToken) : "";
            } catch (ForgeException ex) {
                configured = false;
                maskedGh = "ROTATION_REQUIRED";
            }
        }
        return identityMapper.toTenantDto(tenant, maskedWebhook, maskedGh, configured);
    }

    private String sanitizeIp(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return "unknown";
        }
        String first = rawIp.split(",")[0].trim();
        return first.length() > 64 ? first.substring(0, 64) : first;
    }
}
