package com.forge.platform.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.common.ForgeException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GithubIngestionClient {

    private static final Logger log = LoggerFactory.getLogger(GithubIngestionClient.class);
    private static final int MAX_FILES_TO_FETCH = 18;
    private static final int MAX_FILE_BYTES = 45_000;

    public record PullRequestSnapshot(
        String owner,
        String repo,
        int prNumber,
        String title,
        String author,
        String sourceBranch,
        String targetBranch,
        String commitSha,
        String htmlUrl,
        String rawDiff
    ) {}

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GithubIngestionClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(6));
        factory.setReadTimeout(Duration.ofSeconds(12));
        this.restClient = RestClient.builder()
            .requestFactory(factory)
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("User-Agent", "forge-ai-platform/1.0")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
    }

    @CircuitBreaker(name = "githubApi", fallbackMethod = "fallbackEmpty")
    @Retry(name = "githubApi")
    public Map<String, String> fetchPublicRepositoryFiles(String owner, String repo, String branch) {
        return fetchRepositoryFiles(owner, repo, branch, null);
    }

    public Map<String, String> fetchRepositoryFiles(String owner, String repo, String branch, String githubToken) {
        validateSegment(owner);
        validateSegment(repo);
        String safeBranch = (branch == null || branch.isBlank()) ? "main" : branch.replaceAll("[^a-zA-Z0-9._/-]", "");
        Map<String, String> files = new LinkedHashMap<>();

        String treeUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/git/trees/" + safeBranch + "?recursive=1";
        var treeSpec = restClient.get().uri(treeUrl);
        if (githubToken != null && !githubToken.isBlank()) {
            treeSpec = treeSpec.header("Authorization", "Bearer " + githubToken.trim());
        }
        String treeJson = treeSpec.retrieve().body(String.class);
        if (treeJson == null) {
            return files;
        }

        try {
            JsonNode root = objectMapper.readTree(treeJson);
            JsonNode tree = root.path("tree");
            if (!tree.isArray()) {
                return files;
            }
            for (JsonNode entry : tree) {
                if (files.size() >= MAX_FILES_TO_FETCH) break;
                String type = entry.path("type").asText();
                String path = entry.path("path").asText();
                int size = entry.path("size").asInt(0);
                if (!"blob".equals(type) || size == 0 || size > MAX_FILE_BYTES) continue;
                if (!isSupportedSourcePath(path)) continue;

                String rawUrl = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + safeBranch + "/" + path;
                try {
                    var rawSpec = restClient.get().uri(rawUrl);
                    if (githubToken != null && !githubToken.isBlank()) {
                        rawSpec = rawSpec.header("Authorization", "Bearer " + githubToken.trim());
                    }
                    String content = rawSpec.retrieve().body(String.class);
                    if (content != null && !content.isBlank()) {
                        files.put(path, content);
                    }
                } catch (Exception ex) {
                    log.debug("Skipping raw file {} due to fetch error: {}", path, ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to parse GitHub tree for {}/{}: {}", owner, repo, ex.getMessage());
        }
        return files;
    }

    /**
     * Fetches real Pull Request metadata and unified git diff directly from GitHub REST API.
     */
    public PullRequestSnapshot fetchPullRequest(String owner, String repo, int prNumber, String githubToken) {
        validateSegment(owner);
        validateSegment(repo);
        if (prNumber <= 0) {
            throw new ForgeException(HttpStatus.BAD_REQUEST, "INVALID_PR_NUMBER", "Pull request number must be positive");
        }

        String prApiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
        try {
            var metaSpec = restClient.get()
                .uri(prApiUrl)
                .header("Accept", "application/vnd.github+json");
            if (githubToken != null && !githubToken.isBlank()) {
                metaSpec = metaSpec.header("Authorization", "Bearer " + githubToken.trim());
            }
            String metaJson = metaSpec.retrieve().body(String.class);
            JsonNode pr = objectMapper.readTree(metaJson);

            var diffSpec = restClient.get()
                .uri(prApiUrl)
                .header("Accept", "application/vnd.github.v3.diff");
            if (githubToken != null && !githubToken.isBlank()) {
                diffSpec = diffSpec.header("Authorization", "Bearer " + githubToken.trim());
            }
            String rawDiff = diffSpec.retrieve().body(String.class);
            if (rawDiff == null || rawDiff.isBlank()) {
                throw new ForgeException(HttpStatus.BAD_REQUEST, "EMPTY_PR_DIFF",
                    "GitHub PR #" + prNumber + " has no diff changes to analyze.");
            }

            return new PullRequestSnapshot(
                owner,
                repo,
                prNumber,
                pr.path("title").asText("GitHub PR #" + prNumber),
                pr.path("user").path("login").asText("github-user"),
                pr.path("head").path("ref").asText("feature/pr-" + prNumber),
                pr.path("base").path("ref").asText("main"),
                pr.path("head").path("sha").asText("github-sha"),
                pr.path("html_url").asText("https://github.com/" + owner + "/" + repo + "/pull/" + prNumber),
                rawDiff
            );
        } catch (ForgeException ex) {
            throw ex;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ForgeException(HttpStatus.NOT_FOUND, "GITHUB_PR_NOT_FOUND",
                "GitHub PR " + owner + "/" + repo + "#" + prNumber
                    + " not found. If this is a private repo, save a GitHub Personal Access Token in Settings first.");
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            throw new ForgeException(HttpStatus.BAD_GATEWAY, "GITHUB_AUTH_ERROR",
                "GitHub API rejected the request (" + ex.getStatusCode().value()
                    + "). Check your GitHub Personal Access Token in Settings or API rate limits.");
        } catch (Exception ex) {
            throw new ForgeException(HttpStatus.BAD_GATEWAY, "GITHUB_FETCH_FAILED",
                "Could not fetch PR from GitHub: " + ex.getMessage());
        }
    }

    /**
     * Publishes a real Markdown review comment onto a real GitHub Pull Request and returns the comment's html_url.
     */
    public String postPullRequestComment(
        String owner, String repo, int prNumber, String markdownBody, String githubToken
    ) {
        validateSegment(owner);
        validateSegment(repo);
        if (githubToken == null || githubToken.isBlank()) {
            throw new ForgeException(HttpStatus.BAD_REQUEST, "GITHUB_TOKEN_REQUIRED",
                "Configure a GitHub Personal Access Token in Settings to post live comments on GitHub.");
        }

        String commentApiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/issues/" + prNumber + "/comments";
        try {
            String resp = restClient.post()
                .uri(commentApiUrl)
                .header("Authorization", "Bearer " + githubToken.trim())
                .header("Accept", "application/vnd.github+json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", markdownBody))
                .retrieve()
                .body(String.class);

            JsonNode json = objectMapper.readTree(resp);
            String htmlUrl = json.path("html_url").asText("");
            log.info("Published live FORGE review comment to GitHub: {}", htmlUrl);
            return htmlUrl;
        } catch (HttpClientErrorException ex) {
            throw new ForgeException(HttpStatus.BAD_GATEWAY, "GITHUB_PUBLISH_FAILED",
                "GitHub rejected comment publish (HTTP " + ex.getStatusCode().value()
                    + "). Make sure your GitHub Token has 'repo' or 'Pull requests: Read & write' permission on "
                    + owner + "/" + repo + ".");
        } catch (Exception ex) {
            throw new ForgeException(HttpStatus.BAD_GATEWAY, "GITHUB_PUBLISH_FAILED",
                "Failed to post review comment to GitHub: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, String> fallbackEmpty(String owner, String repo, String branch, Throwable cause) {
        log.warn("GitHub API circuit fallback for {}/{}: {}", owner, repo, cause.getMessage());
        return Map.of();
    }

    private void validateSegment(String segment) {
        if (segment == null || !segment.matches("^[A-Za-z0-9_.-]+$")) {
            throw new IllegalArgumentException("Invalid GitHub repository identifier: " + segment);
        }
    }

    private boolean isSupportedSourcePath(String path) {
        if (path.contains("node_modules/") || path.contains("dist/") || path.contains("target/")) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".ts") || lower.endsWith(".tsx")
            || lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".sql")
            || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".xml");
    }
}
