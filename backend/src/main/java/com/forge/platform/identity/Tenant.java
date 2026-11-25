package com.forge.platform.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String plan = "ENTERPRISE";

    @Column(name = "webhook_secret_encrypted", columnDefinition = "TEXT")
    private String webhookSecretEncrypted;

    @Column(name = "github_token_encrypted", columnDefinition = "TEXT")
    private String githubTokenEncrypted;

    @Column(name = "ai_provider", nullable = false)
    private String aiProvider = "HYBRID";

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion = "forge-review-v2.4";

    @Column(name = "require_approval_before_publish", nullable = false)
    private boolean requireApprovalBeforePublish = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Tenant() {}

    public static Tenant create(String name, String slug, String webhookSecretEncrypted) {
        Tenant t = new Tenant();
        t.id = UUID.randomUUID().toString();
        t.name = name;
        t.slug = slug;
        t.plan = "ENTERPRISE";
        t.webhookSecretEncrypted = webhookSecretEncrypted;
        t.aiProvider = "HYBRID";
        t.promptVersion = "forge-review-v2.4";
        t.requireApprovalBeforePublish = true;
        t.createdAt = Instant.now();
        return t;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public String getPlan() { return plan; }
    public String getWebhookSecretEncrypted() { return webhookSecretEncrypted; }
    public void setWebhookSecretEncrypted(String webhookSecretEncrypted) { this.webhookSecretEncrypted = webhookSecretEncrypted; }
    public String getGithubTokenEncrypted() { return githubTokenEncrypted; }
    public void setGithubTokenEncrypted(String githubTokenEncrypted) { this.githubTokenEncrypted = githubTokenEncrypted; }
    public String getAiProvider() { return aiProvider; }
    public void setAiProvider(String aiProvider) { this.aiProvider = aiProvider; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public boolean isRequireApprovalBeforePublish() { return requireApprovalBeforePublish; }
    public void setRequireApprovalBeforePublish(boolean requireApprovalBeforePublish) { this.requireApprovalBeforePublish = requireApprovalBeforePublish; }
    public Instant getCreatedAt() { return createdAt; }
}
