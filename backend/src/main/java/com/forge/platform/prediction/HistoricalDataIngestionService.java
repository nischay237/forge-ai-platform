package com.forge.platform.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditService;
import com.forge.platform.common.ForgeException;
import com.forge.platform.common.RateLimitExceededException;
import com.forge.platform.common.ResourceNotFoundException;
import com.forge.platform.common.SecretCryptoService;
import com.forge.platform.common.TenantContext;
import com.forge.platform.messaging.CiBuildRecordedListener;
import com.forge.platform.messaging.EngineeringWebhookListener;
import com.forge.platform.messaging.ForgeCacheService;
import com.forge.platform.messaging.RateLimiterService;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.RepositoryJpaRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Idempotent, tenant-scoped historical engineering data ingestion layer (Milestone 2).
 *
 * Accepts real historical outcomes (CI test executions, deployments & rollbacks, confirmed
 * production incidents, confirmed defect records) via authenticated APIs, CI build diagnostics,
 * or signed GitHub webhooks (workflow_run, deployment_status). Never fabricates missing records.
 */
@Service
public class HistoricalDataIngestionService implements EngineeringWebhookListener, CiBuildRecordedListener {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDataIngestionService.class);
    public static final String OVERVIEW_CACHE_PREFIX = "pred-overview:";
    private static final Pattern CI_TEST_NAME_PATTERN = Pattern.compile("\\b([A-Z][A-Za-z0-9_]{2,80}(?:Test|Tests|IT|Spec))\\b");
    private static final Instant EARLIEST_VALID_TIMESTAMP = Instant.parse("1990-01-01T00:00:00Z");
    private static final Duration MAX_FUTURE_SKEW = Duration.ofHours(24);

    private static final Set<String> VALID_TEST_OUTCOMES = Set.of("PASSED", "FAILED", "SKIPPED", "FLAKY");
    private static final Set<String> VALID_DEPLOY_OUTCOMES = Set.of("SUCCEEDED", "FAILED", "ROLLED_BACK");
    private static final Set<String> VALID_INCIDENT_SEVERITIES = Set.of("SEV1", "SEV2", "SEV3", "SEV4");
    private static final Set<String> VALID_INCIDENT_STATUSES = Set.of("OPEN", "MITIGATED", "RESOLVED");
    private static final Set<String> VALID_DEFECT_SEVERITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    private static final Set<String> VALID_DEFECT_STATUSES = Set.of("OPEN", "FIXED");

    private final RepositoryJpaRepository repositoryJpaRepository;
    private final EngineeringEventRepository engineeringEventRepository;
    private final HistoricalTestExecutionRepository testExecutionRepository;
    private final DeploymentRecordRepository deploymentRecordRepository;
    private final IncidentRecordRepository incidentRecordRepository;
    private final DefectRecordRepository defectRecordRepository;
    private final SecretCryptoService cryptoService;
    private final ForgeCacheService cacheService;
    private final RateLimiterService rateLimiterService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTx;
    private final Counter historicalIngestedCounter;

    public HistoricalDataIngestionService(
        RepositoryJpaRepository repositoryJpaRepository,
        EngineeringEventRepository engineeringEventRepository,
        HistoricalTestExecutionRepository testExecutionRepository,
        DeploymentRecordRepository deploymentRecordRepository,
        IncidentRecordRepository incidentRecordRepository,
        DefectRecordRepository defectRecordRepository,
        SecretCryptoService cryptoService,
        ForgeCacheService cacheService,
        RateLimiterService rateLimiterService,
        AuditService auditService,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        MeterRegistry meterRegistry
    ) {
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.engineeringEventRepository = engineeringEventRepository;
        this.testExecutionRepository = testExecutionRepository;
        this.deploymentRecordRepository = deploymentRecordRepository;
        this.incidentRecordRepository = incidentRecordRepository;
        this.defectRecordRepository = defectRecordRepository;
        this.cryptoService = cryptoService;
        this.cacheService = cacheService;
        this.rateLimiterService = rateLimiterService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.historicalIngestedCounter = Counter.builder("forge.historical.ingested").register(meterRegistry);
    }

    @Transactional
    public PredictionDtos.IngestionResultDto ingestTestExecution(PredictionDtos.IngestTestExecutionRequest req) {
        String tenantId = TenantContext.requireTenantId();
        enforceIngestionRateLimit(tenantId);
        return ingestTestExecutionForTenant(
            tenantId,
            TenantContext.requireEmail(),
            TenantContext.requireRole(),
            "api",
            req
        );
    }

    @Transactional
    public PredictionDtos.IngestionResultDto ingestTestExecutionForTenant(
        String tenantId, String actorEmail, String actorRole, String source,
        PredictionDtos.IngestTestExecutionRequest req
    ) {
        requireRepo(req.repositoryId(), tenantId);
        String outcome = normalizeEnum(req.outcome(), VALID_TEST_OUTCOMES, "INVALID_TEST_OUTCOME",
            "Test outcome must be one of PASSED, FAILED, SKIPPED, FLAKY");
        Instant executedAt = validateTimestamp(req.executedAt(), "executedAt");
        String extKey = resolveExternalKey("test", req.externalEventKey(),
            req.repositoryId() + "|" + req.testName().trim() + "|" + nullSafe(req.commitSha()) + "|"
                + nullSafe(req.prNumber()) + "|" + outcome + "|" + executedAt.toEpochMilli());

        Optional<HistoricalTestExecutionEntity> existing = testExecutionRepository
            .findByTenantIdAndExternalEventKey(tenantId, extKey);
        if (existing.isPresent()) {
            return new PredictionDtos.IngestionResultDto(
                existing.get().getId(), "TEST_EXECUTION", true, extKey, existing.get().getExecutedAt()
            );
        }

        HistoricalTestExecutionEntity candidate = HistoricalTestExecutionEntity.create(
            tenantId,
            req.repositoryId(),
            req.ciBuildId(),
            req.prNumber(),
            req.commitSha(),
            req.testName().trim(),
            trimOrNull(req.testFilePath()),
            deriveModuleName(req.moduleName(), req.testFilePath()),
            outcome,
            req.durationMs() != null ? req.durationMs() : 0L,
            trimOrNull(req.errorSummary()),
            extKey,
            executedAt
        );
        HistoricalTestExecutionEntity saved;
        if (req.ciBuildId() != null && !req.ciBuildId().isBlank()) {
            // Join caller's active transaction so FK ci_build_id -> ci_builds(id) sees uncommitted parent build
            saved = testExecutionRepository.save(candidate);
        } else {
            try {
                saved = requiresNewTx.execute(s -> testExecutionRepository.saveAndFlush(candidate));
            } catch (DataIntegrityViolationException dup) {
                HistoricalTestExecutionEntity raceWinner = testExecutionRepository
                    .findByTenantIdAndExternalEventKey(tenantId, extKey).orElseThrow(() -> dup);
                return new PredictionDtos.IngestionResultDto(
                    raceWinner.getId(), "TEST_EXECUTION", true, extKey, raceWinner.getExecutedAt()
                );
            }
        }

        recordEngineeringEvent(tenantId, req.repositoryId(), extKey, "TEST_EXECUTION", source,
            req.prNumber(), req.commitSha(), null, req, executedAt);
        evictOverviewCache(tenantId);
        auditService.record(tenantId, actorEmail, actorRole, "HISTORICAL_TEST_INGESTED", "TEST_EXECUTION",
            saved.getId(), "Ingested test execution '" + saved.getTestName() + "' (" + outcome + ")", source);

        return new PredictionDtos.IngestionResultDto(saved.getId(), "TEST_EXECUTION", false, extKey, saved.getExecutedAt());
    }

    @Transactional
    public PredictionDtos.IngestionResultDto ingestDeployment(PredictionDtos.IngestDeploymentRequest req) {
        String tenantId = TenantContext.requireTenantId();
        enforceIngestionRateLimit(tenantId);
        return ingestDeploymentForTenant(
            tenantId,
            TenantContext.requireEmail(),
            TenantContext.requireRole(),
            "api",
            req
        );
    }

    @Transactional
    public PredictionDtos.IngestionResultDto ingestDeploymentForTenant(
        String tenantId, String actorEmail, String actorRole, String source,
        PredictionDtos.IngestDeploymentRequest req
    ) {
        requireRepo(req.repositoryId(), tenantId);
        String outcome = normalizeEnum(req.outcome(), VALID_DEPLOY_OUTCOMES, "INVALID_DEPLOYMENT_OUTCOME",
            "Deployment outcome must be one of SUCCEEDED, FAILED, ROLLED_BACK");
        Instant deployedAt = validateTimestamp(req.deployedAt(), "deployedAt");
        String env = (req.environment() == null || req.environment().isBlank()) ? "production" : req.environment().trim();
        String extKey = resolveExternalKey("deploy", req.externalEventKey(),
            req.repositoryId() + "|" + req.serviceName().trim() + "|" + env + "|"
                + nullSafe(req.commitSha()) + "|" + outcome + "|" + deployedAt.toEpochMilli());

        Optional<DeploymentRecordEntity> existing = deploymentRecordRepository
            .findByTenantIdAndExternalEventKey(tenantId, extKey);
        if (existing.isPresent()) {
            return new PredictionDtos.IngestionResultDto(
                existing.get().getId(), "DEPLOYMENT", true, extKey, existing.get().getDeployedAt()
            );
        }

        DeploymentRecordEntity candidate = DeploymentRecordEntity.create(
            tenantId,
            req.repositoryId(),
            req.serviceName().trim(),
            env,
            req.prNumber(),
            trimOrNull(req.commitSha()),
            outcome,
            trimOrNull(req.rollbackOfDeploymentId()),
            Boolean.TRUE.equals(req.changedConfig()),
            trimOrNull(req.summary()),
            extKey,
            deployedAt
        );
        DeploymentRecordEntity saved;
        try {
            saved = requiresNewTx.execute(s -> deploymentRecordRepository.saveAndFlush(candidate));
        } catch (DataIntegrityViolationException dup) {
            DeploymentRecordEntity raceWinner = deploymentRecordRepository
                .findByTenantIdAndExternalEventKey(tenantId, extKey).orElseThrow(() -> dup);
            return new PredictionDtos.IngestionResultDto(
                raceWinner.getId(), "DEPLOYMENT", true, extKey, raceWinner.getDeployedAt()
            );
        }

        recordEngineeringEvent(tenantId, req.repositoryId(), extKey,
            "ROLLED_BACK".equals(outcome) ? "DEPLOYMENT_ROLLBACK" : "DEPLOYMENT",
            source, req.prNumber(), req.commitSha(), saved.getServiceName(), req, deployedAt);
        evictOverviewCache(tenantId);
        auditService.record(tenantId, actorEmail, actorRole, "DEPLOYMENT_RECORD_INGESTED", "DEPLOYMENT",
            saved.getId(), "Recorded " + env + " deployment for '" + saved.getServiceName() + "' (" + outcome + ")", source);

        return new PredictionDtos.IngestionResultDto(saved.getId(), "DEPLOYMENT", false, extKey, saved.getDeployedAt());
    }

    @Transactional
    public PredictionDtos.IngestionResultDto ingestIncident(PredictionDtos.IngestIncidentRequest req) {
        String tenantId = TenantContext.requireTenantId();
        enforceIngestionRateLimit(tenantId);
        requireRepo(req.repositoryId(), tenantId);
        String severity = normalizeEnum(req.severity(), VALID_INCIDENT_SEVERITIES, "INVALID_INCIDENT_SEVERITY",
            "Incident severity must be one of SEV1, SEV2, SEV3, SEV4");
        String status = normalizeEnum(
            req.status() == null || req.status().isBlank() ? "RESOLVED" : req.status(),
            VALID_INCIDENT_STATUSES,
            "INVALID_INCIDENT_STATUS",
            "Incident status must be one of OPEN, MITIGATED, RESOLVED"
        );
        Instant occurredAt = validateTimestamp(req.occurredAt(), "occurredAt");
        Instant resolvedAt = req.resolvedAt() != null ? validateTimestamp(req.resolvedAt(), "resolvedAt") : null;
        if (resolvedAt != null && resolvedAt.isBefore(occurredAt)) {
            throw new ForgeException(HttpStatus.BAD_REQUEST, "INVALID_TIMESTAMP_RANGE",
                "Incident resolvedAt cannot be earlier than occurredAt");
        }
        String extKey = resolveExternalKey("incident", req.externalEventKey(),
            req.repositoryId() + "|" + req.serviceName().trim() + "|" + req.title().trim() + "|" + occurredAt.toEpochMilli());

        Optional<IncidentRecordEntity> existing = incidentRecordRepository
            .findByTenantIdAndExternalEventKey(tenantId, extKey);
        if (existing.isPresent()) {
            return new PredictionDtos.IngestionResultDto(
                existing.get().getId(), "INCIDENT", true, extKey, existing.get().getOccurredAt()
            );
        }

        List<String> modules = req.affectedModules() == null ? List.of()
            : req.affectedModules().stream().filter(m -> m != null && !m.isBlank()).map(String::trim).distinct().toList();

        IncidentRecordEntity candidate = IncidentRecordEntity.create(
            tenantId,
            req.repositoryId(),
            req.title().trim(),
            severity,
            req.serviceName().trim(),
            writeJsonSafe(modules, "[]"),
            trimOrNull(req.relatedDeploymentId()),
            req.relatedPrNumber(),
            status,
            trimOrNull(req.evidenceNote()),
            extKey,
            occurredAt,
            resolvedAt
        );
        IncidentRecordEntity saved;
        try {
            saved = requiresNewTx.execute(s -> incidentRecordRepository.saveAndFlush(candidate));
        } catch (DataIntegrityViolationException dup) {
            IncidentRecordEntity raceWinner = incidentRecordRepository
                .findByTenantIdAndExternalEventKey(tenantId, extKey).orElseThrow(() -> dup);
            return new PredictionDtos.IngestionResultDto(
                raceWinner.getId(), "INCIDENT", true, extKey, raceWinner.getOccurredAt()
            );
        }

        recordEngineeringEvent(tenantId, req.repositoryId(), extKey, "CONFIRMED_INCIDENT", "api",
            req.relatedPrNumber(), null, saved.getServiceName(), req, occurredAt);
        evictOverviewCache(tenantId);
        auditService.recordCurrent("INCIDENT_RECORD_INGESTED", "INCIDENT", saved.getId(),
            "Recorded confirmed " + severity + " incident on '" + saved.getServiceName() + "': " + saved.getTitle());

        return new PredictionDtos.IngestionResultDto(saved.getId(), "INCIDENT", false, extKey, saved.getOccurredAt());
    }

    @Transactional
    public PredictionDtos.IngestionResultDto ingestDefect(PredictionDtos.IngestDefectRequest req) {
        String tenantId = TenantContext.requireTenantId();
        enforceIngestionRateLimit(tenantId);
        requireRepo(req.repositoryId(), tenantId);
        String severity = normalizeEnum(req.severity(), VALID_DEFECT_SEVERITIES, "INVALID_DEFECT_SEVERITY",
            "Defect severity must be one of CRITICAL, HIGH, MEDIUM, LOW");
        String status = normalizeEnum(
            req.status() == null || req.status().isBlank() ? "OPEN" : req.status(),
            VALID_DEFECT_STATUSES,
            "INVALID_DEFECT_STATUS",
            "Defect status must be OPEN or FIXED"
        );
        Instant reportedAt = validateTimestamp(req.reportedAt(), "reportedAt");
        String extKey = resolveExternalKey("defect", req.externalEventKey(),
            req.repositoryId() + "|" + req.title().trim() + "|" + nullSafe(req.moduleName()) + "|" + reportedAt.toEpochMilli());

        Optional<DefectRecordEntity> existing = defectRecordRepository
            .findByTenantIdAndExternalEventKey(tenantId, extKey);
        if (existing.isPresent()) {
            return new PredictionDtos.IngestionResultDto(
                existing.get().getId(), "DEFECT", true, extKey, existing.get().getReportedAt()
            );
        }

        List<String> files = req.filePaths() == null ? List.of()
            : req.filePaths().stream().filter(f -> f != null && !f.isBlank()).map(String::trim).distinct().toList();
        String moduleName = deriveModuleName(req.moduleName(), files.isEmpty() ? null : files.get(0));

        DefectRecordEntity candidate = DefectRecordEntity.create(
            tenantId,
            req.repositoryId(),
            req.title().trim(),
            severity,
            moduleName,
            writeJsonSafe(files, "[]"),
            req.introducedPrNumber(),
            req.resolvedPrNumber(),
            status,
            extKey,
            reportedAt
        );
        DefectRecordEntity saved;
        try {
            saved = requiresNewTx.execute(s -> defectRecordRepository.saveAndFlush(candidate));
        } catch (DataIntegrityViolationException dup) {
            DefectRecordEntity raceWinner = defectRecordRepository
                .findByTenantIdAndExternalEventKey(tenantId, extKey).orElseThrow(() -> dup);
            return new PredictionDtos.IngestionResultDto(
                raceWinner.getId(), "DEFECT", true, extKey, raceWinner.getReportedAt()
            );
        }

        recordEngineeringEvent(tenantId, req.repositoryId(), extKey, "CONFIRMED_DEFECT", "api",
            req.introducedPrNumber(), null, moduleName, req, reportedAt);
        evictOverviewCache(tenantId);
        auditService.recordCurrent("DEFECT_RECORD_INGESTED", "DEFECT", saved.getId(),
            "Recorded confirmed " + severity + " defect (" + saved.getTitle() + ")");

        return new PredictionDtos.IngestionResultDto(saved.getId(), "DEFECT", false, extKey, saved.getReportedAt());
    }

    // --- Signed GitHub Webhook Ingestion (workflow_run & deployment_status) ---

    @Override
    public boolean supportsGithubEvent(String eventName) {
        return "workflow_run".equalsIgnoreCase(eventName) || "deployment_status".equalsIgnoreCase(eventName);
    }

    @Override
    @Transactional
    public Map<String, Object> onGithubEvent(
        String tenantId, String eventName, String deliveryKey, String rawPayloadJson
    ) {
        try {
            JsonNode root = objectMapper.readTree(rawPayloadJson);
            String repoFullName = root.path("repository").path("full_name").asText("");
            RepositoryEntity repo = repositoryJpaRepository
                .findByTenantIdAndFullNameIgnoreCase(tenantId, repoFullName)
                .orElse(null);
            if (repo == null) {
                return Map.of("status", "IGNORED_UNCONNECTED_REPO", "repository", repoFullName);
            }

            if ("workflow_run".equalsIgnoreCase(eventName)) {
                JsonNode run = root.path("workflow_run");
                String conclusion = run.path("conclusion").asText("");
                if (conclusion.isBlank() || "null".equalsIgnoreCase(conclusion)) {
                    return Map.of("status", "IGNORED_IN_PROGRESS", "event", eventName);
                }
                String outcome = switch (conclusion.toLowerCase(Locale.ROOT)) {
                    case "success" -> "PASSED";
                    case "skipped", "neutral", "cancelled" -> "SKIPPED";
                    default -> "FAILED";
                };
                String name = run.path("name").asText("github-workflow");
                String sha = run.path("head_sha").asText(null);
                Integer prNumber = null;
                JsonNode prs = run.path("pull_requests");
                if (prs.isArray() && !prs.isEmpty()) {
                    int n = prs.get(0).path("number").asInt(0);
                    if (n > 0) prNumber = n;
                }
                Instant runTimestamp = parseWebhookTimestamp(run.path("updated_at").asText(run.path("created_at").asText("")));
                PredictionDtos.IngestionResultDto res = ingestTestExecutionForTenant(
                    tenantId, "github-webhook@external", "WEBHOOK", "github-webhook",
                    new PredictionDtos.IngestTestExecutionRequest(
                        repo.getId(), null, prNumber, sha, name, null, repo.getName(),
                        outcome, 0L, "FAILED".equals(outcome) ? "GitHub workflow conclusion: " + conclusion : null,
                        deliveryKey, runTimestamp
                    )
                );
                return Map.of("status", "INGESTED", "recordType", res.recordType(), "id", res.id(), "duplicate", res.duplicate());
            }

            if ("deployment_status".equalsIgnoreCase(eventName)) {
                JsonNode statusNode = root.path("deployment_status");
                JsonNode depNode = root.path("deployment");
                String state = statusNode.path("state").asText("");
                if (!List.of("success", "failure", "error").contains(state.toLowerCase(Locale.ROOT))) {
                    return Map.of("status", "IGNORED_INTERMEDIATE_STATE", "state", state);
                }
                String desc = statusNode.path("description").asText("");
                boolean isRollback = desc.toLowerCase(Locale.ROOT).contains("rollback")
                    || desc.toLowerCase(Locale.ROOT).contains("revert");
                String outcome = isRollback ? "ROLLED_BACK"
                    : ("success".equalsIgnoreCase(state) ? "SUCCEEDED" : "FAILED");
                String env = depNode.path("environment").asText("production");
                String sha = depNode.path("sha").asText(null);
                Instant deployTimestamp = parseWebhookTimestamp(
                    statusNode.path("updated_at").asText(statusNode.path("created_at").asText(""))
                );
                PredictionDtos.IngestionResultDto res = ingestDeploymentForTenant(
                    tenantId, "github-webhook@external", "WEBHOOK", "github-webhook",
                    new PredictionDtos.IngestDeploymentRequest(
                        repo.getId(), repo.getName(), env, null, sha, outcome, null,
                        false, desc.isBlank() ? "GitHub deployment_status: " + state : desc,
                        deliveryKey, deployTimestamp
                    )
                );
                return Map.of("status", "INGESTED", "recordType", res.recordType(), "id", res.id(), "duplicate", res.duplicate());
            }

            return Map.of("status", "IGNORED_EVENT", "event", eventName);
        } catch (ForgeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Failed to parse GitHub {} webhook payload: {}", eventName, ex.getMessage());
            throw new ForgeException(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK_PAYLOAD",
                "Invalid or malformed GitHub webhook payload");
        }
    }

    @Override
    @Transactional
    public void onCiBuildRecorded(
        String tenantId, String repositoryId, String ciBuildId, String pipelineName,
        String commitSha, Integer prNumber, String status, int durationSeconds,
        String failureCategory, String rawLog, Instant triggeredAt
    ) {
        String outcome = "PASSED".equalsIgnoreCase(status) ? "PASSED" : "FAILED";
        Set<String> extractedTests = new LinkedHashSet<>();
        if (rawLog != null && !rawLog.isBlank()) {
            Matcher m = CI_TEST_NAME_PATTERN.matcher(rawLog);
            while (m.find() && extractedTests.size() < 5) {
                extractedTests.add(m.group(1));
            }
        }
        if (extractedTests.isEmpty()) {
            extractedTests.add(pipelineName != null && !pipelineName.isBlank() ? pipelineName.trim() : "ci-pipeline");
        }
        long durationMs = Math.max(0L, durationSeconds * 1000L);
        String errNote = "FAILED".equals(outcome) && failureCategory != null ? "CI failure category: " + failureCategory : null;

        for (String testName : extractedTests) {
            ingestTestExecutionForTenant(
                tenantId, "ci-bridge@forge.dev", "SYSTEM", "ci-build",
                new PredictionDtos.IngestTestExecutionRequest(
                    repositoryId, ciBuildId, prNumber, commitSha, testName, null, null,
                    outcome, durationMs, errNote, "ci-build:" + ciBuildId + ":" + testName, triggeredAt
                )
            );
        }
    }

    private void enforceIngestionRateLimit(String tenantId) {
        if (!rateLimiterService.allowRequest("hist-ingest:" + tenantId, 120, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("Rate limit exceeded for historical outcome ingestion. Please retry shortly.");
        }
    }

    private RepositoryEntity requireRepo(String repositoryId, String tenantId) {
        return repositoryJpaRepository.findByIdAndTenantId(repositoryId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Repository not found: " + repositoryId));
    }

    private void recordEngineeringEvent(
        String tenantId, String repoId, String extKey, String eventType, String source,
        Integer prNumber, String commitSha, String serviceName, Object payload, Instant occurredAt
    ) {
        if (engineeringEventRepository.findByTenantIdAndExternalEventKey(tenantId, extKey).isEmpty()) {
            engineeringEventRepository.save(EngineeringEventEntity.create(
                tenantId, repoId, extKey, eventType, source, prNumber, commitSha, serviceName,
                writeJsonSafe(payload, "{}"), occurredAt
            ));
            historicalIngestedCounter.increment();
        }
    }

    private void evictOverviewCache(String tenantId) {
        cacheService.evict(OVERVIEW_CACHE_PREFIX + tenantId);
    }

    private Instant validateTimestamp(Instant candidate, String fieldName) {
        Instant resolved = candidate != null ? candidate : Instant.now();
        Instant maxAllowed = Instant.now().plus(MAX_FUTURE_SKEW);
        if (resolved.isAfter(maxAllowed) || resolved.isBefore(EARLIEST_VALID_TIMESTAMP)) {
            throw new ForgeException(HttpStatus.BAD_REQUEST, "INVALID_TIMESTAMP",
                "Timestamp '" + fieldName + "' must be between 1990-01-01 and 24 hours from now");
        }
        return resolved;
    }

    private Instant parseWebhookTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return validateTimestamp(Instant.parse(raw.trim()), "webhookTimestamp");
        } catch (Exception ex) {
            return Instant.now();
        }
    }

    private String normalizeEnum(String raw, Set<String> allowed, String code, String msg) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new ForgeException(HttpStatus.BAD_REQUEST, code, msg);
        }
        return normalized;
    }

    private String resolveExternalKey(String prefix, String explicitKey, String fingerprintSeed) {
        if (explicitKey != null && !explicitKey.isBlank()) {
            String trimmed = explicitKey.trim();
            return trimmed.length() > 180 ? trimmed.substring(0, 180) : trimmed;
        }
        return prefix + ":" + cryptoService.sha256Hex(fingerprintSeed).substring(0, 40);
    }

    static String deriveModuleName(String explicitModule, String filePath) {
        if (explicitModule != null && !explicitModule.isBlank()) {
            return explicitModule.trim();
        }
        if (filePath == null || filePath.isBlank()) {
            return "general";
        }
        String norm = filePath.replace('\\', '/');
        String[] parts = norm.split("/");
        for (int i = parts.length - 2; i >= 0; i--) {
            String seg = parts[i].toLowerCase(Locale.ROOT);
            if (!seg.isBlank() && !Set.of("src", "main", "test", "java", "com", "org", "platform", "pages", "lib").contains(seg)) {
                return parts[i];
            }
        }
        return parts.length >= 2 ? parts[0] : "general";
    }

    private String trimOrNull(String val) {
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    private String nullSafe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String writeJsonSafe(Object obj, String fallback) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            return fallback;
        }
    }
}
