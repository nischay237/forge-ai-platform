package com.forge.platform.prediction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "engineering_events")
public class EngineeringEventEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id")
    private String repositoryId;

    @Column(name = "external_event_key", nullable = false)
    private String externalEventKey;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String source;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "commit_sha")
    private String commitSha;

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    public EngineeringEventEntity() {}

    public static EngineeringEventEntity create(
        String tenantId, String repositoryId, String externalEventKey, String eventType,
        String source, Integer prNumber, String commitSha, String serviceName,
        String payloadJson, Instant occurredAt
    ) {
        EngineeringEventEntity e = new EngineeringEventEntity();
        e.id = UUID.randomUUID().toString();
        e.tenantId = tenantId;
        e.repositoryId = repositoryId;
        e.externalEventKey = externalEventKey;
        e.eventType = eventType;
        e.source = source;
        e.prNumber = prNumber;
        e.commitSha = commitSha;
        e.serviceName = serviceName;
        e.payloadJson = payloadJson;
        e.occurredAt = occurredAt != null ? occurredAt : Instant.now();
        e.ingestedAt = Instant.now();
        return e;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getExternalEventKey() { return externalEventKey; }
    public String getEventType() { return eventType; }
    public String getSource() { return source; }
    public Integer getPrNumber() { return prNumber; }
    public String getCommitSha() { return commitSha; }
    public String getServiceName() { return serviceName; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getIngestedAt() { return ingestedAt; }
}
