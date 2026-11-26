package com.forge.platform.security;

import com.forge.platform.audit.AuditService;
import com.forge.platform.common.ForgeException;
import com.forge.platform.common.ResourceNotFoundException;
import com.forge.platform.common.SecretCryptoService;
import com.forge.platform.common.TenantContext;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.RepositoryService;
import com.forge.platform.repository.SourceFileEntity;
import com.forge.platform.repository.SourceFileJpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SecurityService {

    private static final Set<String> VALID_STATUSES = Set.of("OPEN", "MITIGATED", "FALSE_POSITIVE", "SUPPRESSED");

    public record CreateSuppressionRequest(
        String repositoryId,
        String fingerprint,
        String ruleId,
        String reason,
        Instant expiresAt
    ) {}

    private final SecurityFindingRepository findingRepository;
    private final FindingSuppressionRepository suppressionRepository;
    private final SourceFileJpaRepository sourceFileJpaRepository;
    private final RepositoryService repositoryService;
    private final SecurityScannerEngine scannerEngine;
    private final DependencyAndSbomService dependencyAndSbomService;
    private final SarifExportService sarifExportService;
    private final SecretCryptoService cryptoService;
    private final AuditService auditService;

    public SecurityService(
        SecurityFindingRepository findingRepository,
        FindingSuppressionRepository suppressionRepository,
        SourceFileJpaRepository sourceFileJpaRepository,
        RepositoryService repositoryService,
        SecurityScannerEngine scannerEngine,
        DependencyAndSbomService dependencyAndSbomService,
        SarifExportService sarifExportService,
        SecretCryptoService cryptoService,
        AuditService auditService
    ) {
        this.findingRepository = findingRepository;
        this.suppressionRepository = suppressionRepository;
        this.sourceFileJpaRepository = sourceFileJpaRepository;
        this.repositoryService = repositoryService;
        this.scannerEngine = scannerEngine;
        this.dependencyAndSbomService = dependencyAndSbomService;
        this.sarifExportService = sarifExportService;
        this.cryptoService = cryptoService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<SecurityFindingEntity> listFindings(String repositoryId, String severity, String status) {
        String tenantId = TenantContext.requireTenantId();
        List<SecurityFindingEntity> base = (repositoryId != null && !repositoryId.isBlank())
            ? findingRepository.findByTenantIdAndRepositoryIdOrderByDetectedAtDesc(tenantId, repositoryId)
            : findingRepository.findByTenantIdOrderByDetectedAtDesc(tenantId);

        return base.stream()
            .filter(f -> severity == null || severity.isBlank() || f.getSeverity().equalsIgnoreCase(severity))
            .filter(f -> status == null || status.isBlank() || f.getStatus().equalsIgnoreCase(status))
            .toList();
    }

    @Transactional
    public SecurityFindingEntity triageFinding(String findingId, String newStatus) {
        String normalized = newStatus == null ? "" : newStatus.trim().toUpperCase();
        if (!VALID_STATUSES.contains(normalized)) {
            throw new ForgeException(HttpStatus.BAD_REQUEST, "INVALID_STATUS",
                "Status must be one of: OPEN, MITIGATED, FALSE_POSITIVE, SUPPRESSED");
        }
        String tenantId = TenantContext.requireTenantId();
        SecurityFindingEntity finding = findingRepository.findByIdAndTenantId(findingId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Security finding not found"));
        String oldStatus = finding.getStatus();
        finding.updateStatus(normalized);
        findingRepository.save(finding);

        auditService.recordCurrent("SECURITY_FINDING_TRIAGED", "SECURITY_FINDING", finding.getId(),
            "Changed " + finding.getRuleId() + " (" + finding.getFilePath() + ") status from " + oldStatus + " to " + normalized);
        return finding;
    }

    @Transactional
    public List<SecurityFindingEntity> scanRepository(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        RepositoryEntity repo = repositoryService.requireRepository(repositoryId, tenantId);
        List<SourceFileEntity> files = sourceFileJpaRepository
            .findByRepositoryIdAndTenantIdOrderByFilePathAsc(repositoryId, tenantId);

        List<SecurityFindingEntity> existing = new ArrayList<>(findingRepository
            .findByTenantIdAndRepositoryIdOrderByDetectedAtDesc(tenantId, repositoryId));
        Set<String> activeSuppressedFingerprints = activeSuppressedFingerprints(tenantId);

        List<SecurityScannerEngine.DetectedSecurityIssue> detected = new ArrayList<>();
        for (SourceFileEntity sf : files) {
            detected.addAll(scannerEngine.scanFullFile(sf.getFilePath(), sf.getContent()));
        }
        // Also run Software Composition Analysis (SCA) on pom.xml / package.json manifests
        detected.addAll(dependencyAndSbomService.scanDependencies(files));

        List<SecurityFindingEntity> newlyCreated = new ArrayList<>();
        for (var issue : detected) {
            String fp = computeFingerprint(issue.filePath(), issue.ruleId(), issue.maskedEvidence());
            boolean alreadyTracked = existing.stream().anyMatch(e ->
                (fp.equals(e.getFingerprint()))
                    || (e.getRuleId().equals(issue.ruleId())
                    && e.getFilePath().equals(issue.filePath())
                    && e.getLineNumber() == issue.lineNumber())
            );
            if (!alreadyTracked) {
                SecurityFindingEntity entity = SecurityFindingEntity.create(
                    tenantId, repositoryId, null,
                    issue.scanner(), issue.ruleId(), issue.cweId(), issue.severity(),
                    issue.title(), issue.filePath(), issue.lineNumber(),
                    issue.maskedEvidence(), issue.remediation()
                );
                entity.setFingerprint(fp);
                if (activeSuppressedFingerprints.contains(fp)) {
                    entity.updateStatus("SUPPRESSED");
                }
                SecurityFindingEntity saved = findingRepository.save(entity);
                newlyCreated.add(saved);
                existing.add(saved);
            }
        }

        auditService.recordCurrent("SECURITY_SCAN_EXECUTED", "REPOSITORY", repositoryId,
            "Executed Secret/Entropy + SAST + IaC + SCA scan on " + repo.getFullName() + " (" + newlyCreated.size() + " new findings)");
        return existing;
    }

    @Transactional
    public List<SecurityFindingEntity> recordFindingsForReview(
        String tenantId, String repositoryId, String reviewId,
        List<SecurityScannerEngine.DetectedSecurityIssue> issues
    ) {
        Set<String> activeSuppressed = activeSuppressedFingerprints(tenantId);
        List<SecurityFindingEntity> saved = new ArrayList<>();
        for (var issue : issues) {
            String fp = computeFingerprint(issue.filePath(), issue.ruleId(), issue.maskedEvidence());
            SecurityFindingEntity entity = SecurityFindingEntity.create(
                tenantId, repositoryId, reviewId,
                issue.scanner(), issue.ruleId(), issue.cweId(), issue.severity(),
                issue.title(), issue.filePath(), issue.lineNumber(),
                issue.maskedEvidence(), issue.remediation()
            );
            entity.setFingerprint(fp);
            if (activeSuppressed.contains(fp)) {
                entity.updateStatus("SUPPRESSED");
            }
            saved.add(findingRepository.save(entity));
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportRepositorySbom(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        RepositoryEntity repo = repositoryService.requireRepository(repositoryId, tenantId);
        List<SourceFileEntity> files = sourceFileJpaRepository
            .findByRepositoryIdAndTenantIdOrderByFilePathAsc(repositoryId, tenantId);
        return dependencyAndSbomService.generateCycloneDxSbom(repo, files);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportRepositorySarif(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        RepositoryEntity repo = repositoryService.requireRepository(repositoryId, tenantId);
        List<SecurityFindingEntity> findings = findingRepository
            .findByTenantIdAndRepositoryIdOrderByDetectedAtDesc(tenantId, repositoryId);
        return sarifExportService.buildSarifReport(repo, findings);
    }

    @Transactional(readOnly = true)
    public List<FindingSuppressionEntity> listSuppressions() {
        return suppressionRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());
    }

    @Transactional
    public FindingSuppressionEntity createSuppression(CreateSuppressionRequest request) {
        if (request.fingerprint() == null || request.fingerprint().isBlank()
            || request.ruleId() == null || request.ruleId().isBlank()
            || request.reason() == null || request.reason().trim().length() < 5) {
            throw new ForgeException(HttpStatus.BAD_REQUEST, "INVALID_SUPPRESSION",
                "Suppression requires fingerprint, ruleId, and a justification reason (min 5 chars).");
        }
        String tenantId = TenantContext.requireTenantId();
        String actorEmail = TenantContext.requireEmail();
        String fp = request.fingerprint().trim();

        FindingSuppressionEntity saved = suppressionRepository.findByTenantIdAndFingerprint(tenantId, fp)
            .orElseGet(() -> suppressionRepository.save(FindingSuppressionEntity.create(
                tenantId, request.repositoryId(), fp, request.ruleId().trim(),
                request.reason(), actorEmail, request.expiresAt()
            )));

        // Mark matching open findings as SUPPRESSED
        List<SecurityFindingEntity> findings = findingRepository.findByTenantIdOrderByDetectedAtDesc(tenantId);
        for (SecurityFindingEntity f : findings) {
            if (fp.equals(f.getFingerprint()) && "OPEN".equals(f.getStatus())) {
                f.updateStatus("SUPPRESSED");
                findingRepository.save(f);
            }
        }

        auditService.recordCurrent("FINDING_SUPPRESSED", "FINDING_SUPPRESSION", saved.getId(),
            "Suppressed rule " + saved.getRuleId() + " (fp=" + fp.substring(0, Math.min(12, fp.length())) + ") reason: " + saved.getReason());
        return saved;
    }

    @Transactional
    public void deleteSuppression(String suppressionId) {
        String tenantId = TenantContext.requireTenantId();
        FindingSuppressionEntity entity = suppressionRepository.findByIdAndTenantId(suppressionId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Suppression not found: " + suppressionId));
        suppressionRepository.delete(entity);
        auditService.recordCurrent("FINDING_SUPPRESSION_REMOVED", "FINDING_SUPPRESSION", suppressionId,
            "Removed suppression for rule " + entity.getRuleId());
    }

    public String computeFingerprint(String filePath, String ruleId, String maskedEvidence) {
        String normalized = (maskedEvidence == null ? "" : maskedEvidence).replaceAll("\\s+", " ").trim();
        return cryptoService.sha256Hex(filePath + "|" + ruleId + "|" + normalized);
    }

    private Set<String> activeSuppressedFingerprints(String tenantId) {
        return suppressionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
            .filter(FindingSuppressionEntity::isActive)
            .map(FindingSuppressionEntity::getFingerprint)
            .collect(Collectors.toSet());
    }
}
