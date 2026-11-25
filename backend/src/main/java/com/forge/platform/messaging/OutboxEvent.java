package com.forge.platform.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public OutboxEvent() {}

    public static OutboxEvent pending(String tenantId, String aggregateType, String aggregateId,
                                      String eventType, String topic, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.id = UUID.randomUUID().toString();
        event.tenantId = tenantId;
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.topic = topic;
        event.payload = payload;
        event.status = "PENDING";
        event.retryCount = 0;
        event.nextAttemptAt = Instant.now();
        event.createdAt = Instant.now();
        return event;
    }

    public void markPublished() {
        this.status = "PUBLISHED";
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    public void recordFailure(String error, int maxRetries) {
        this.retryCount++;
        this.lastError = error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
        if (this.retryCount >= maxRetries) {
            this.status = "DEAD_LETTER";
        } else {
            long backoffSeconds = (long) Math.pow(2, this.retryCount);
            this.nextAttemptAt = Instant.now().plusSeconds(backoffSeconds);
        }
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
