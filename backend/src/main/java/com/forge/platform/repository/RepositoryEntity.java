package com.forge.platform.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repositories")
public class RepositoryEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch = "main";

    @Column(name = "primary_language", nullable = false)
    private String primaryLanguage = "Java";

    @Column(name = "github_url", nullable = false)
    private String githubUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "index_status", nullable = false)
    private String indexStatus = "PENDING";

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "symbol_count", nullable = false)
    private int symbolCount;

    @Column(name = "dependency_count", nullable = false)
    private int dependencyCount;

    @Column(name = "webhook_configured", nullable = false)
    private boolean webhookConfigured = true;

    @Column(name = "last_indexed_at")
    private Instant lastIndexedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public RepositoryEntity() {}

    public static RepositoryEntity create(String tenantId, String owner, String name, String defaultBranch,
                                          String primaryLanguage, String githubUrl, String description) {
        RepositoryEntity repo = new RepositoryEntity();
        repo.id = UUID.randomUUID().toString();
        repo.tenantId = tenantId;
        repo.owner = owner.trim();
        repo.name = name.trim();
        repo.fullName = repo.owner + "/" + repo.name;
        repo.defaultBranch = (defaultBranch == null || defaultBranch.isBlank()) ? "main" : defaultBranch.trim();
        repo.primaryLanguage = (primaryLanguage == null || primaryLanguage.isBlank()) ? "Java" : primaryLanguage.trim();
        repo.githubUrl = githubUrl;
        repo.description = description;
        repo.indexStatus = "INDEXING";
        repo.webhookConfigured = true;
        repo.createdAt = Instant.now();
        return repo;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public String getFullName() { return fullName; }
    public String getDefaultBranch() { return defaultBranch; }
    public String getPrimaryLanguage() { return primaryLanguage; }
    public void setPrimaryLanguage(String primaryLanguage) { this.primaryLanguage = primaryLanguage; }
    public String getGithubUrl() { return githubUrl; }
    public String getDescription() { return description; }
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }
    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }
    public int getSymbolCount() { return symbolCount; }
    public void setSymbolCount(int symbolCount) { this.symbolCount = symbolCount; }
    public int getDependencyCount() { return dependencyCount; }
    public void setDependencyCount(int dependencyCount) { this.dependencyCount = dependencyCount; }
    public boolean isWebhookConfigured() { return webhookConfigured; }
    public void setWebhookConfigured(boolean webhookConfigured) { this.webhookConfigured = webhookConfigured; }
    public Instant getLastIndexedAt() { return lastIndexedAt; }
    public void setLastIndexedAt(Instant lastIndexedAt) { this.lastIndexedAt = lastIndexedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
