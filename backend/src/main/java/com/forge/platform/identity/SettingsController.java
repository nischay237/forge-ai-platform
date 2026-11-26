package com.forge.platform.identity;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final AuthService authService;

    public SettingsController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public AuthDtos.WorkspaceSettingsResponse getSettings() {
        return authService.getWorkspaceSettings();
    }

    @PatchMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public AuthDtos.TenantDto updateSettings(@Valid @RequestBody AuthDtos.UpdateSettingsRequest request) {
        return authService.updateWorkspaceSettings(request);
    }

    @PostMapping("/rotate-webhook-secret")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public AuthDtos.WebhookSecretRotationResponse rotateWebhookSecret() {
        return authService.rotateWebhookSecret();
    }
}
