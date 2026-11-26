package com.forge.platform.prediction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "defect_records")
public class DefectRecordEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id", nullable = false)
    private String repositoryId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String severity;

    @Column(name = "module_name")
    private String moduleName;

    @Column(name = "file_paths_json", columnDefinition = "TEXT")
    private String filePathsJson;

    @Column(name = "introduced_pr_number")
    private Integer introducedPrNumber;

    @Column(name = "resolved_pr_number")
    private Integer resolvedPrNumber;

    @Column(nullable = false)
    private String status;

    @Column(name = "external_event_key")
    private String externalEventKey;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    public DefectRecordEntity() {}

    public static DefectRecordEntity create(
        String tenantId, String repositoryId, String title, String severity, String moduleName,
        String filePathsJson, Integer introducedPrNumber, Integer resolvedPrNumber,
        String status, String externalEventKey, Instant reportedAt
    ) {
        DefectRecordEntity d = new DefectRecordEntity();
        d.id = UUID.randomUUID().toString();
        d.tenantId = tenantId;
        d.repositoryId = repositoryId;
        d.title = title;
        d.severity = severity;
        d.moduleName = moduleName;
        d.filePathsJson = filePathsJson;
        d.introducedPrNumber = introducedPrNumber;
        d.resolvedPrNumber = resolvedPrNumber;
        d.status = (status == null || status.isBlank()) ? "OPEN" : status;
        d.externalEventKey = externalEventKey;
        d.reportedAt = reportedAt != null ? reportedAt : Instant.now();
        return d;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getTitle() { return title; }
    public String getSeverity() { return severity; }
    public String getModuleName() { return moduleName; }
    public String getFilePathsJson() { return filePathsJson; }
    public Integer getIntroducedPrNumber() { return introducedPrNumber; }
    public Integer getResolvedPrNumber() { return resolvedPrNumber; }
    public String getStatus() { return status; }
    public String getExternalEventKey() { return externalEventKey; }
    public Instant getReportedAt() { return reportedAt; }
}
