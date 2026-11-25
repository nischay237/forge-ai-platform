package com.forge.platform.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_key")
    private String eventKey;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedEvent() {}

    public ProcessedEvent(String eventKey, String tenantId, String eventType) {
        this.eventKey = eventKey;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    public String getEventKey() { return eventKey; }
    public String getTenantId() { return tenantId; }
    public String getEventType() { return eventType; }
    public Instant getProcessedAt() { return processedAt; }
}
