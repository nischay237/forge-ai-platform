package com.forge.platform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class AuthDtos {

    public record RegisterRequest(
        @NotBlank @Size(min = 2, max = 100) String workspaceName,
        @NotBlank @Size(min = 2, max = 100) String fullName,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) String password
    ) {}

    public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
    ) {}

    public record RefreshRequest(
        @NotBlank String refreshToken
    ) {}

    public record LogoutRequest(
        String refreshToken
    ) {}

    public record UserDto(
        String id,
        String tenantId,
        String email,
        String fullName,
        String role,
        Instant createdAt
    ) {}

    public record TenantDto(
        String id,
        String name,
        String slug,
        String plan,
        String aiProvider,
        String promptVersion,
        boolean requireApprovalBeforePublish,
        String maskedWebhookSecret,
        String maskedGithubToken,
        boolean githubTokenConfigured,
        Instant createdAt
    ) {}

    public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UserDto user,
        TenantDto tenant,
        String initialWebhookSecret
    ) {}

    public record SessionResponse(
        UserDto user,
        TenantDto tenant
    ) {}

    public record WorkspaceSettingsResponse(
        TenantDto tenant,
        List<UserDto> members,
        String activeProfile,
        boolean openaiConfigured
    ) {}

    public record UpdateSettingsRequest(
        @Size(min = 2, max = 120) String workspaceName,
        String aiProvider,
        String promptVersion,
        Boolean requireApprovalBeforePublish,
        String newWebhookSecret,
        String newGithubToken
    ) {}

    public record WebhookSecretRotationResponse(
        String webhookSecret,
        TenantDto tenant
    ) {}
}
