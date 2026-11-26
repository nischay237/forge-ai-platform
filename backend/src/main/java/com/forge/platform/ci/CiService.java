package com.forge.platform.ci;

import com.forge.platform.audit.AuditService;
import com.forge.platform.common.ResourceNotFoundException;
import com.forge.platform.common.TenantContext;
import com.forge.platform.messaging.CiBuildRecordedListener;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.RepositoryJpaRepository;
import com.forge.platform.repository.RepositoryService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CiService {

    public record SubmitBuildRequest(
        @NotBlank String repositoryId,
        @NotBlank String pipelineName,
        @NotBlank String branch,
        String commitSha,
        Integer prNumber,
        String status,
        Integer durationSeconds,
        @NotBlank String rawLog
    ) {}

    public record CiBuildDto(
        String id,
        String repositoryId,
        String repositoryName,
        String pipelineName,
        String branch,
        String commitSha,
        Integer prNumber,
        String status,
        int durationSeconds,
        String failureCategory,
        String rootCauseSummary,
        String remediationSteps,
        String rawLog,
        Instant triggeredAt
    ) {}

    private final CiBuildRepository ciBuildRepository;
    private final RepositoryJpaRepository repositoryJpaRepository;
    private final RepositoryService repositoryService;
    private final CiFailureAnalyzer analyzer;
    private final AuditService auditService;
    private final ObjectProvider<CiBuildRecordedListener> ciBuildListeners;

    public CiService(
        CiBuildRepository ciBuildRepository,
        RepositoryJpaRepository repositoryJpaRepository,
        RepositoryService repositoryService,
        CiFailureAnalyzer analyzer,
        AuditService auditService,
        ObjectProvider<CiBuildRecordedListener> ciBuildListeners
    ) {
        this.ciBuildRepository = ciBuildRepository;
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.repositoryService = repositoryService;
        this.analyzer = analyzer;
        this.auditService = auditService;
        this.ciBuildListeners = ciBuildListeners;
    }

    @Transactional(readOnly = true)
    public List<CiBuildDto> listBuilds(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        List<CiBuildEntity> builds = (repositoryId != null && !repositoryId.isBlank())
            ? ciBuildRepository.findByTenantIdAndRepositoryIdOrderByTriggeredAtDesc(tenantId, repositoryId)
            : ciBuildRepository.findByTenantIdOrderByTriggeredAtDesc(tenantId);

        Map<String, String> repoNames = new HashMap<>();
        repositoryJpaRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
            .forEach(r -> repoNames.put(r.getId(), r.getFullName()));

        return builds.stream()
            .map(b -> toDto(b, repoNames.getOrDefault(b.getRepositoryId(), "repository")))
            .toList();
    }

    @Transactional(readOnly = true)
    public CiBuildDto getBuild(String buildId) {
        String tenantId = TenantContext.requireTenantId();
        CiBuildEntity build = ciBuildRepository.findByIdAndTenantId(buildId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("CI build not found"));
        String repoName = repositoryJpaRepository.findByIdAndTenantId(build.getRepositoryId(), tenantId)
            .map(RepositoryEntity::getFullName).orElse("repository");
        return toDto(build, repoName);
    }

    @Transactional
    public CiBuildDto submitAndDiagnose(SubmitBuildRequest req) {
        String tenantId = TenantContext.requireTenantId();
        return submitForTenant(tenantId, TenantContext.requireEmail(), TenantContext.requireRole(), req);
    }

    @Transactional
    public CiBuildDto submitForTenant(String tenantId, String actorEmail, String actorRole, SubmitBuildRequest req) {
        RepositoryEntity repo = repositoryService.requireRepository(req.repositoryId(), tenantId);
        CiFailureAnalyzer.Diagnosis diag = analyzer.diagnose(req.status(), req.rawLog());
        String sha = (req.commitSha() == null || req.commitSha().isBlank()) ? "head-" + System.currentTimeMillis() % 100000 : req.commitSha();
        int duration = req.durationSeconds() != null && req.durationSeconds() > 0 ? req.durationSeconds() : 84;

        CiBuildEntity saved = ciBuildRepository.save(CiBuildEntity.create(
            tenantId, repo.getId(), req.pipelineName().trim(), req.branch().trim(), sha,
            req.prNumber(), diag.status(), duration, diag.failureCategory(),
            diag.rootCauseSummary(), diag.remediationSteps(), req.rawLog()
        ));

        auditService.record(tenantId, actorEmail, actorRole,
            "CI_BUILD_DIAGNOSED", "CI_BUILD", saved.getId(),
            "Analyzed pipeline '" + saved.getPipelineName() + "' (" + saved.getStatus() + " / " + saved.getFailureCategory() + ")",
            "ci");

        ciBuildListeners.orderedStream().forEach(listener -> listener.onCiBuildRecorded(
            tenantId, repo.getId(), saved.getId(), saved.getPipelineName(), saved.getCommitSha(),
            saved.getPrNumber(), saved.getStatus(), saved.getDurationSeconds(),
            saved.getFailureCategory(), saved.getRawLog(), saved.getTriggeredAt()
        ));

        return toDto(saved, repo.getFullName());
    }

    private CiBuildDto toDto(CiBuildEntity b, String repoName) {
        return new CiBuildDto(
            b.getId(), b.getRepositoryId(), repoName, b.getPipelineName(),
            b.getBranch(), b.getCommitSha(), b.getPrNumber(), b.getStatus(),
            b.getDurationSeconds(), b.getFailureCategory(), b.getRootCauseSummary(),
            b.getRemediationSteps(), b.getRawLog(), b.getTriggeredAt()
        );
    }
}
