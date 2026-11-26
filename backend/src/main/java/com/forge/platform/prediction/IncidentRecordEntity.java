package com.forge.platform.prediction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incident_records")
public class IncidentRecordEntity {

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

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(name = "affected_modules_json", columnDefinition = "TEXT")
    private String affectedModulesJson;

    @Column(name = "related_deployment_id")
    private String relatedDeploymentId;

    @Column(name = "related_pr_number")
    private Integer relatedPrNumber;

    @Column(nullable = false)
    private String status;

    @Column(name = "evidence_note", columnDefinition = "TEXT")
    private String evidenceNote;

    @Column(name = "external_event_key")
    private String externalEventKey;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public IncidentRecordEntity() {}

    public static IncidentRecordEntity create(
        String tenantId, String repositoryId, String title, String severity, String serviceName,
        String affectedModulesJson, String relatedDeploymentId, Integer relatedPrNumber,
        String status, String evidenceNote, String externalEventKey, Instant occurredAt, Instant resolvedAt
    ) {
        IncidentRecordEntity i = new IncidentRecordEntity();
        i.id = UUID.randomUUID().toString();
        i.tenantId = tenantId;
        i.repositoryId = repositoryId;
        i.title = title;
        i.severity = severity;
        i.serviceName = serviceName;
        i.affectedModulesJson = affectedModulesJson;
        i.relatedDeploymentId = relatedDeploymentId;
        i.relatedPrNumber = relatedPrNumber;
        i.status = (status == null || status.isBlank()) ? "RESOLVED" : status;
        i.evidenceNote = evidenceNote;
        i.externalEventKey = externalEventKey;
        i.occurredAt = occurredAt != null ? occurredAt : Instant.now();
        i.resolvedAt = resolvedAt;
        return i;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getTitle() { return title; }
    public String getSeverity() { return severity; }
    public String getServiceName() { return serviceName; }
    public String getAffectedModulesJson() { return affectedModulesJson; }
    public String getRelatedDeploymentId() { return relatedDeploymentId; }
    public Integer getRelatedPrNumber() { return relatedPrNumber; }
    public String getStatus() { return status; }
    public String getEvidenceNote() { return evidenceNote; }
    public String getExternalEventKey() { return externalEventKey; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
