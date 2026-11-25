package com.forge.platform.repository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public final class RepositoryDtos {

    public record ConnectRepositoryRequest(
        @NotBlank @Size(max = 100) String owner,
        @NotBlank @Size(max = 140) String name,
        String defaultBranch,
        String primaryLanguage,
        String description,
        boolean fetchFromGithub,
        Map<String, String> initialFiles
    ) {}

    public record RepositoryDto(
        String id,
        String owner,
        String name,
        String fullName,
        String defaultBranch,
        String primaryLanguage,
        String githubUrl,
        String description,
        String indexStatus,
        int fileCount,
        int symbolCount,
        int dependencyCount,
        boolean webhookConfigured,
        Instant lastIndexedAt,
        Instant createdAt
    ) {}

    public record SourceFileSummaryDto(
        String id,
        String filePath,
        String language,
        int lineCount,
        int complexityScore,
        Instant indexedAt
    ) {}

    public record SourceFileDetailDto(
        String id,
        String repositoryId,
        String filePath,
        String language,
        int lineCount,
        int complexityScore,
        String content,
        Instant indexedAt
    ) {}
}
