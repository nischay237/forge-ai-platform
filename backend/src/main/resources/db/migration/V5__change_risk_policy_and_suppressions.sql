-- V5: Change Risk Intelligence Engine, Policy-as-Code, Finding Fingerprints & Suppression Workflow

ALTER TABLE pull_request_reviews ADD COLUMN merge_decision VARCHAR(16) NOT NULL DEFAULT 'WARN';
ALTER TABLE pull_request_reviews ADD COLUMN risk_breakdown_json TEXT;
ALTER TABLE pull_request_reviews ADD COLUMN recommended_tests_json TEXT;

ALTER TABLE review_findings ADD COLUMN fingerprint VARCHAR(64);
ALTER TABLE security_findings ADD COLUMN fingerprint VARCHAR(64);
CREATE INDEX idx_security_findings_fp ON security_findings(tenant_id, fingerprint);

CREATE TABLE finding_suppressions (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    repository_id VARCHAR(36) REFERENCES repositories(id) ON DELETE CASCADE,
    fingerprint VARCHAR(64) NOT NULL,
    rule_id VARCHAR(80) NOT NULL,
    reason TEXT NOT NULL,
    suppressed_by VARCHAR(160) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_suppression_tenant_fp UNIQUE (tenant_id, fingerprint)
);
CREATE INDEX idx_suppressions_tenant ON finding_suppressions(tenant_id, repository_id);

CREATE TABLE change_risk_policies (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    repository_id VARCHAR(36) REFERENCES repositories(id) ON DELETE CASCADE,
    version INT NOT NULL DEFAULT 1,
    block_on_critical_security BOOLEAN NOT NULL DEFAULT TRUE,
    block_on_breaking_api BOOLEAN NOT NULL DEFAULT TRUE,
    block_on_destructive_migration BOOLEAN NOT NULL DEFAULT TRUE,
    block_on_prompt_injection BOOLEAN NOT NULL DEFAULT TRUE,
    max_risk_score_for_pass INT NOT NULL DEFAULT 25,
    max_risk_score_for_warn INT NOT NULL DEFAULT 65,
    min_tests_for_high_impact BOOLEAN NOT NULL DEFAULT TRUE,
    sensitive_paths_json TEXT,
    architecture_rules_json TEXT,
    updated_by VARCHAR(160) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_risk_policies_tenant ON change_risk_policies(tenant_id, repository_id);
