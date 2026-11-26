-- V4: P0 Security Hardening, Rotating Refresh Tokens, Outbox Leases, Idempotency Leases & Composite Tenant Keys

ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMP WITH TIME ZONE;

CREATE TABLE refresh_tokens (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    replaced_by_hash VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(tenant_id, user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);

ALTER TABLE outbox_events ADD COLUMN locked_by VARCHAR(80);
ALTER TABLE outbox_events ADD COLUMN locked_until TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_outbox_tenant_status ON outbox_events(tenant_id, status);

ALTER TABLE processed_events ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE processed_events ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE;

ALTER TABLE pull_request_reviews ADD COLUMN publish_status VARCHAR(32) NOT NULL DEFAULT 'NOT_PUBLISHED';

ALTER TABLE repositories ADD CONSTRAINT uq_repositories_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE pull_request_reviews ADD CONSTRAINT uq_pr_reviews_tenant_id UNIQUE (tenant_id, id);
