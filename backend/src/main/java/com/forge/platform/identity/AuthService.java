package com.forge.platform.identity;

import com.forge.platform.audit.AuditService;
import com.forge.platform.common.ForgeException;
import com.forge.platform.common.ResourceNotFoundException;
import com.forge.platform.common.SecretCryptoService;
import com.forge.platform.common.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class AuthService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecretCryptoService cryptoService;
    private final IdentityMapper identityMapper;
    private final AuditService auditService;
    private final Environment environment;
    private final String defaultWebhookSecret;
    private final String openAiApiKey;
    private final String defaultGithubToken;

    public AuthService(
        TenantRepository tenantRepository,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        SecretCryptoService cryptoService,
        IdentityMapper identityMapper,
        AuditService auditService,
        Environment environment,
        @Value("${forge.security.default-webhook-secret}") String defaultWebhookSecret,
        @Value("${forge.ai.openai-api-key:}") String openAiApiKey,
        @Value("${forge.github.token:}") String defaultGithubToken
    ) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.cryptoService = cryptoService;
        this.identityMapper = identityMapper;
        this.auditService = auditService;
        this.environment = environment;
        this.defaultWebhookSecret = defaultWebhookSecret;
        this.openAiApiKey = openAiApiKey;
        this.defaultGithubToken = defaultGithubToken;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ForgeException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "An account with this email already exists");
        }

        String baseSlug = request.workspaceName().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        String slug = baseSlug.isBlank() ? "workspace" : baseSlug;
        if (tenantRepository.findBySlug(slug).isPresent()) {
            slug = slug + "-" + System.currentTimeMillis() % 10000;
        }

        Tenant tenant = Tenant.create(request.workspaceName().trim(), slug, cryptoService.encrypt(defaultWebhookSecret));
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
            "Created workspace '" + tenant.getName() + "' with OWNER account", "api");

        return buildAuthResponse(user, tenant);
    }

    @Transactional
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.email())
            .orElseThrow(() -> new ForgeException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password"));

        if (!user.isActive() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }

        Tenant tenant = tenantRepository.findById(user.getTenantId())
            .orElseThrow(() -> new ResourceNotFoundException("Workspace not found"));

        auditService.record(tenant.getId(), user.getEmail(), user.getRole().name(),
            "USER_LOGIN", "USER", user.getId(), "Authenticated via JWT", "api");

        return buildAuthResponse(user, tenant);
    }

    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse currentSession() {
        TenantContext.PrincipalContext principal = TenantContext.get();
        if (principal == null) {
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        User user = userRepository.findById(principal.userId())
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Tenant tenant = tenantRepository.findById(principal.tenantId())
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
        return buildAuthResponse(user, tenant);
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
            tenant.setAiProvider(request.aiProvider().trim().toUpperCase(Locale.ROOT));
        }
        if (request.promptVersion() != null && !request.promptVersion().isBlank()) {
            tenant.setPromptVersion(request.promptVersion().trim());
        }
        if (request.requireApprovalBeforePublish() != null) {
            tenant.setRequireApprovalBeforePublish(request.requireApprovalBeforePublish());
        }
        if (request.newWebhookSecret() != null && !request.newWebhookSecret().isBlank()) {
            tenant.setWebhookSecretEncrypted(cryptoService.encrypt(request.newWebhookSecret().trim()));
        }
        if (request.newGithubToken() != null) {
            String trimmedToken = request.newGithubToken().trim();
            if (trimmedToken.isBlank()) {
                tenant.setGithubTokenEncrypted(null);
            } else {
                tenant.setGithubTokenEncrypted(cryptoService.encrypt(trimmedToken));
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

    public String resolveTenantWebhookSecret(Tenant tenant) {
        if (tenant.getWebhookSecretEncrypted() != null && !tenant.getWebhookSecretEncrypted().isBlank()) {
            try {
                return cryptoService.decrypt(tenant.getWebhookSecretEncrypted());
            } catch (Exception ignored) {
                // fallback to default secret if encryption key rotated in dev
            }
        }
        return defaultWebhookSecret;
    }

    public String resolveTenantGithubToken(Tenant tenant) {
        if (tenant.getGithubTokenEncrypted() != null && !tenant.getGithubTokenEncrypted().isBlank()) {
            try {
                String decrypted = cryptoService.decrypt(tenant.getGithubTokenEncrypted());
                if (decrypted != null && !decrypted.isBlank()) {
                    return decrypted;
                }
            } catch (Exception ignored) {
                // ignore stale encrypted token
            }
        }
        return defaultGithubToken != null ? defaultGithubToken.trim() : "";
    }

    private AuthDtos.AuthResponse buildAuthResponse(User user, Tenant tenant) {
        String token = jwtService.generateToken(user);
        return new AuthDtos.AuthResponse(
            token,
            "Bearer",
            identityMapper.toUserDto(user),
            toTenantDto(tenant)
        );
    }

    private AuthDtos.TenantDto toTenantDto(Tenant tenant) {
        String decrypted = resolveTenantWebhookSecret(tenant);
        String githubToken = resolveTenantGithubToken(tenant);
        boolean configured = githubToken != null && !githubToken.isBlank();
        String maskedGh = configured ? cryptoService.maskSecret(githubToken) : "";
        return identityMapper.toTenantDto(tenant, cryptoService.maskSecret(decrypted), maskedGh, configured);
    }
}
