package com.forge.platform.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_files")
public class SourceFileEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id", nullable = false)
    private String repositoryId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(nullable = false)
    private String language;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "complexity_score", nullable = false)
    private int complexityScore;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    public SourceFileEntity() {}

    public static SourceFileEntity create(String tenantId, String repositoryId, String filePath,
                                          String language, int complexityScore, String contentHash, String content) {
        SourceFileEntity sf = new SourceFileEntity();
        sf.id = UUID.randomUUID().toString();
        sf.tenantId = tenantId;
        sf.repositoryId = repositoryId;
        sf.filePath = filePath;
        sf.language = language;
        sf.lineCount = content == null ? 0 : content.split("\\R", -1).length;
        sf.complexityScore = Math.max(1, complexityScore);
        sf.contentHash = contentHash;
        sf.content = content;
        sf.indexedAt = Instant.now();
        return sf;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getFilePath() { return filePath; }
    public String getLanguage() { return language; }
    public int getLineCount() { return lineCount; }
    public int getComplexityScore() { return complexityScore; }
    public void setComplexityScore(int complexityScore) { this.complexityScore = complexityScore; }
    public String getContentHash() { return contentHash; }
    public String getContent() { return content; }
    public void setContent(String content) {
        this.content = content;
        this.lineCount = content == null ? 0 : content.split("\\R", -1).length;
        this.indexedAt = Instant.now();
    }
    public Instant getIndexedAt() { return indexedAt; }
}
