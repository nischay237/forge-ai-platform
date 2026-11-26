package com.forge.platform.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "security_findings")
public class SecurityFindingEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id", nullable = false)
    private String repositoryId;

    @Column(name = "review_id")
    private String reviewId;

    @Column(nullable = false)
    private String scanner;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Column(name = "cwe_id")
    private String cweId;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private String title;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "masked_evidence", columnDefinition = "TEXT")
    private String maskedEvidence;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String remediation;

    @Column(name = "fingerprint")
    private String fingerprint;

    @Column(nullable = false)
    private String status;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SecurityFindingEntity() {}

    public static SecurityFindingEntity create(
        String tenantId, String repositoryId, String reviewId,
        String scanner, String ruleId, String cweId, String severity,
        String title, String filePath, int lineNumber,
        String maskedEvidence, String remediation
    ) {
        SecurityFindingEntity f = new SecurityFindingEntity();
        f.id = UUID.randomUUID().toString();
        f.tenantId = tenantId;
        f.repositoryId = repositoryId;
        f.reviewId = reviewId;
        f.scanner = scanner;
        f.ruleId = ruleId;
        f.cweId = cweId;
        f.severity = severity;
        f.title = title;
        f.filePath = filePath;
        f.lineNumber = Math.max(1, lineNumber);
        f.maskedEvidence = maskedEvidence;
        f.remediation = remediation;
        f.status = "OPEN";
        f.detectedAt = Instant.now();
        f.updatedAt = Instant.now();
        return f;
    }

    public void updateStatus(String newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getReviewId() { return reviewId; }
    public String getScanner() { return scanner; }
    public String getRuleId() { return ruleId; }
    public String getCweId() { return cweId; }
    public String getSeverity() { return severity; }
    public String getTitle() { return title; }
    public String getFilePath() { return filePath; }
    public int getLineNumber() { return lineNumber; }
    public String getMaskedEvidence() { return maskedEvidence; }
    public String getRemediation() { return remediation; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getStatus() { return status; }
    public Instant getDetectedAt() { return detectedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
