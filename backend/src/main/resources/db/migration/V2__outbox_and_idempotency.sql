-- Transactional Outbox & Idempotent Consumer Deduplication Schema

CREATE TABLE outbox_events (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    topic VARCHAR(120) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_outbox_status_next ON outbox_events(status, next_attempt_at);

CREATE TABLE processed_events (
    event_key VARCHAR(160) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_processed_events_tenant ON processed_events(tenant_id);
