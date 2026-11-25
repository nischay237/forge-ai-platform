-- FORGE Core Schema (PostgreSQL 16 & H2 PostgreSQL-compatibility mode)

CREATE TABLE tenants (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(80) NOT NULL UNIQUE,
    plan VARCHAR(32) NOT NULL DEFAULT 'ENTERPRISE',
    webhook_secret_encrypted TEXT,
    ai_provider VARCHAR(32) NOT NULL DEFAULT 'HYBRID',
    prompt_version VARCHAR(64) NOT NULL DEFAULT 'forge-review-v2.4',
    require_approval_before_publish BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
    email VARCHAR(160) NOT NULL UNIQUE,
    full_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_users_tenant ON users(tenant_id);

CREATE TABLE repositories (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
    owner VARCHAR(100) NOT NULL,
    name VARCHAR(140) NOT NULL,
    full_name VARCHAR(250) NOT NULL,
    default_branch VARCHAR(80) NOT NULL DEFAULT 'main',
    primary_language VARCHAR(40) NOT NULL DEFAULT 'Java',
    github_url VARCHAR(500) NOT NULL,
    description TEXT,
    index_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    file_count INT NOT NULL DEFAULT 0,
    symbol_count INT NOT NULL DEFAULT 0,
    dependency_count INT NOT NULL DEFAULT 0,
    webhook_configured BOOLEAN NOT NULL DEFAULT TRUE,
    last_indexed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_repo_tenant_fullname UNIQUE (tenant_id, full_name)
);
CREATE INDEX idx_repositories_tenant ON repositories(tenant_id);

CREATE TABLE source_files (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
    repository_id VARCHAR(36) NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    file_path VARCHAR(500) NOT NULL,
    language VARCHAR(40) NOT NULL,
    line_count INT NOT NULL DEFAULT 0,
    complexity_score INT NOT NULL DEFAULT 1,
    content_hash VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    indexed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_source_file_repo_path UNIQUE (repository_id, file_path)
);
CREATE INDEX idx_source_files_tenant_repo ON source_files(tenant_id, repository_id);

CREATE TABLE code_symbols (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
    repository_id VARCHAR(36) NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    file_path VARCHAR(500) NOT NULL,
    symbol_name VARCHAR(200) NOT NULL,
    qualified_name VARCHAR(400) NOT NULL,
    symbol_kind VARCHAR(32) NOT NULL,
    start_line INT NOT NULL DEFAULT 1,
    end_line INT NOT NULL DEFAULT 1,
    signature VARCHAR(500),
    complexity INT NOT NULL DEFAULT 1
);
CREATE INDEX idx_code_symbols_tenant_repo ON code_symbols(tenant_id, repository_id);
CREATE INDEX idx_code_symbols_name ON code_symbols(symbol_name);

CREATE TABLE code_dependencies (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
    repository_id VARCHAR(36) NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    source_node VARCHAR(300) NOT NULL,
    source_file VARCHAR(500) NOT NULL,
    target_node VARCHAR(300) NOT NULL,
    target_file VARCHAR(500),
    dependency_type VARCHAR(32) NOT NULL
);
CREATE INDEX idx_code_deps_tenant_repo ON code_dependencies(tenant_id, repository_id);
CREATE INDEX idx_code_deps_target ON code_dependencies(target_node);

CREATE TABLE pull_request_reviews (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
    repository_id VARCHAR(36) NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    pr_number INT NOT NULL,
    title VARCHAR(300) NOT NULL,
    author VARCHAR(120) NOT NULL,
    source_branch VARCHAR(120) NOT NULL,
    target_branch VARCHAR(120) NOT NULL DEFAULT 'main',
    commit_sha VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(64) NOT NULL DEFAULT 'QUEUED',
    risk_score INT NOT NULL DEFAULT 0,
    summary TEXT,
    raw_diff TEXT NOT NULL,
    impacted_symbols_json TEXT,
    prompt_version VARCHAR(64) NOT NULL DEFAULT 'forge-review-v2.4',
    model_used VARCHAR(80) NOT NULL DEFAULT 'forge-ast-hybrid-v2',
    tokens_used INT NOT NULL DEFAULT 0,
    estimated_cost_usd DECIMAL(10, 6) NOT NULL DEFAULT 0,
    processing_ms BIGINT NOT NULL DEFAULT 0,
    published_to_github BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_pr_reviews_tenant ON pull_request_reviews(tenant_id, created_at DESC);
CREATE INDEX idx_pr_reviews_repo ON pull_request_reviews(repository_id);

CREATE TABLE review_findings (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
    review_id VARCHAR(36) NOT NULL REFERENCES pull_request_reviews(id) ON DELETE CASCADE,
    file_path VARCHAR(500) NOT NULL,
    line_number INT NOT NULL DEFAULT 1,
    category VARCHAR(40) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    confidence DECIMAL(4, 2) NOT NULL,
    rule_id VARCHAR(80) NOT NULL,
    title VARCHAR(240) NOT NULL,
    explanation TEXT NOT NULL,
    evidence TEXT,
    suggested_patch TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    reviewed_by VARCHAR(120),
    reviewed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_review_findings_review ON review_findings(review_id);
CREATE INDEX idx_review_findings_tenant ON review_findings(tenant_id);

CREATE TABLE security_findings (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
    repository_id VARCHAR(36) NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    review_id VARCHAR(36),
    scanner VARCHAR(40) NOT NULL,
    rule_id VARCHAR(80) NOT NULL,
    cwe_id VARCHAR(32),
    severity VARCHAR(20) NOT NULL,
    title VARCHAR(240) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    line_number INT NOT NULL DEFAULT 1,
    masked_evidence TEXT,
    remediation TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_security_findings_tenant ON security_findings(tenant_id, severity);

CREATE TABLE ci_builds (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
    repository_id VARCHAR(36) NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    pipeline_name VARCHAR(160) NOT NULL,
    branch VARCHAR(120) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    pr_number INT,
    status VARCHAR(24) NOT NULL,
    duration_seconds INT NOT NULL DEFAULT 0,
    failure_category VARCHAR(64),
    root_cause_summary TEXT,
    remediation_steps TEXT,
    raw_log TEXT NOT NULL,
    triggered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ci_builds_tenant ON ci_builds(tenant_id, triggered_at DESC);

CREATE TABLE audit_logs (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
    actor_email VARCHAR(160) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    action VARCHAR(80) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(80),
    details TEXT,
    ip_address VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_logs_tenant ON audit_logs(tenant_id, created_at DESC);

CREATE TABLE evaluation_runs (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
    prompt_version VARCHAR(64) NOT NULL,
    model_used VARCHAR(80) NOT NULL,
    dataset_size INT NOT NULL,
    true_positives INT NOT NULL,
    false_positives INT NOT NULL,
    false_negatives INT NOT NULL,
    precision_score DECIMAL(5, 4) NOT NULL,
    recall_score DECIMAL(5, 4) NOT NULL,
    f1_score DECIMAL(5, 4) NOT NULL,
    false_positive_rate DECIMAL(5, 4) NOT NULL,
    avg_latency_ms BIGINT NOT NULL,
    case_results_json TEXT NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_eval_runs_tenant ON evaluation_runs(tenant_id, executed_at DESC);
