package com.forge.platform.security;

import com.forge.platform.audit.AuditService;
import com.forge.platform.common.ForgeException;
import com.forge.platform.common.ResourceNotFoundException;
import com.forge.platform.common.TenantContext;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.RepositoryService;
import com.forge.platform.repository.SourceFileEntity;
import com.forge.platform.repository.SourceFileJpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class SecurityService {

    private static final Set<String> VALID_STATUSES = Set.of("OPEN", "MITIGATED", "FALSE_POSITIVE");

    private final SecurityFindingRepository findingRepository;
    private final SourceFileJpaRepository sourceFileJpaRepository;
    private final RepositoryService repositoryService;
    private final SecurityScannerEngine scannerEngine;
    private final AuditService auditService;

    public SecurityService(
        SecurityFindingRepository findingRepository,
        SourceFileJpaRepository sourceFileJpaRepository,
        RepositoryService repositoryService,
        SecurityScannerEngine scannerEngine,
        AuditService auditService
    ) {
        this.findingRepository = findingRepository;
        this.sourceFileJpaRepository = sourceFileJpaRepository;
        this.repositoryService = repositoryService;
        this.scannerEngine = scannerEngine;
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
                "Status must be one of: OPEN, MITIGATED, FALSE_POSITIVE");
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

        List<SecurityFindingEntity> existing = findingRepository
            .findByTenantIdAndRepositoryIdOrderByDetectedAtDesc(tenantId, repositoryId);

        List<SecurityFindingEntity> newlyCreated = new ArrayList<>();
        for (SourceFileEntity sf : files) {
            var issues = scannerEngine.scanFullFile(sf.getFilePath(), sf.getContent());
            for (var issue : issues) {
                boolean alreadyTracked = existing.stream().anyMatch(e ->
                    e.getRuleId().equals(issue.ruleId())
                        && e.getFilePath().equals(issue.filePath())
                        && e.getLineNumber() == issue.lineNumber()
                );
                if (!alreadyTracked) {
                    SecurityFindingEntity saved = findingRepository.save(SecurityFindingEntity.create(
                        tenantId, repositoryId, null,
                        issue.scanner(), issue.ruleId(), issue.cweId(), issue.severity(),
                        issue.title(), issue.filePath(), issue.lineNumber(),
                        issue.maskedEvidence(), issue.remediation()
                    ));
                    newlyCreated.add(saved);
                    existing.add(saved);
                }
            }
        }

        auditService.recordCurrent("SECURITY_SCAN_EXECUTED", "REPOSITORY", repositoryId,
            "Executed Gitleaks + Semgrep security scan on " + repo.getFullName() + " (" + newlyCreated.size() + " new findings)");
        return existing;
    }

    @Transactional
    public List<SecurityFindingEntity> recordFindingsForReview(
        String tenantId, String repositoryId, String reviewId,
        List<SecurityScannerEngine.DetectedSecurityIssue> issues
    ) {
        List<SecurityFindingEntity> saved = new ArrayList<>();
        for (var issue : issues) {
            saved.add(findingRepository.save(SecurityFindingEntity.create(
                tenantId, repositoryId, reviewId,
                issue.scanner(), issue.ruleId(), issue.cweId(), issue.severity(),
                issue.title(), issue.filePath(), issue.lineNumber(),
                issue.maskedEvidence(), issue.remediation()
            )));
        }
        return saved;
    }
}
