-- V6: Predictive Engineering Intelligence Engine — Historical Engineering Data, Predictions, Evidence & Held-Out Evaluations

CREATE TABLE engineering_events (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    repository_id VARCHAR(36) REFERENCES repositories(id) ON DELETE CASCADE,
    external_event_key VARCHAR(180) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    source VARCHAR(48) NOT NULL,
    pr_number INT,
    commit_sha VARCHAR(64),
    service_name VARCHAR(120),
    payload_json TEXT,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ingested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_eng_events_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_eng_events_tenant_ext_key UNIQUE (tenant_id, external_event_key)
);
CREATE INDEX idx_eng_events_tenant_repo_time ON engineering_events(tenant_id, repository_id, occurred_at DESC);

CREATE TABLE historical_test_executions (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    repository_id VARCHAR(36) NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    ci_build_id VARCHAR(36) REFERENCES ci_builds(id) ON DELETE SET NULL,
    pr_number INT,
    commit_sha VARCHAR(64),
    test_name VARCHAR(240) NOT NULL,
    test_file_path VARCHAR(500),
    module_name VARCHAR(160),
    outcome VARCHAR(24) NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_summary TEXT,
    external_event_key VARCHAR(180),
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_hist_tests_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX idx_hist_tests_tenant_repo ON historical_test_executions(tenant_id, repository_id, executed_at DESC);
CREATE INDEX idx_hist_tests_tenant_pr ON historical_test_executions(tenant_id, repository_id, pr_number);
CREATE INDEX idx_hist_tests_tenant_name ON historical_test_executions(tenant_id, repository_id, test_name);
CREATE UNIQUE INDEX uq_hist_tests_tenant_ext_key ON historical_test_executions(tenant_id, external_event_key);

CREATE TABLE deployment_records (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    repository_id VARCHAR(36) NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    service_name VARCHAR(140) NOT NULL,
    environment VARCHAR(48) NOT NULL DEFAULT 'production',
    pr_number INT,
    commit_sha VARCHAR(64),
    outcome VARCHAR(24) NOT NULL,
    rollback_of_deployment_id VARCHAR(36),
    changed_config BOOLEAN NOT NULL DEFAULT FALSE,
    summary TEXT,
    external_event_key VARCHAR(180),
    deployed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_deployments_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX idx_deployments_tenant_repo ON deployment_records(tenant_id, repository_id, deployed_at DESC);
CREATE INDEX idx_deployments_tenant_service ON deployment_records(tenant_id, service_name, deployed_at DESC);
CREATE UNIQUE INDEX uq_deployments_tenant_ext_key ON deployment_records(tenant_id, external_event_key);

CREATE TABLE incident_records (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    repository_id VARCHAR(36) NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    title VARCHAR(260) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    service_name VARCHAR(140) NOT NULL,
    affected_modules_json TEXT,
    related_deployment_id VARCHAR(36),
    related_pr_number INT,
    status VARCHAR(24) NOT NULL DEFAULT 'RESOLVED',
    evidence_note TEXT,
    external_event_key VARCHAR(180),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_incidents_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX idx_incidents_tenant_repo ON incident_records(tenant_id, repository_id, occurred_at DESC);
CREATE INDEX idx_incidents_tenant_service ON incident_records(tenant_id, service_name, occurred_at DESC);
CREATE UNIQUE INDEX uq_incidents_tenant_ext_key ON incident_records(tenant_id, external_event_key);

CREATE TABLE defect_records (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    repository_id VARCHAR(36) NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    title VARCHAR(260) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    module_name VARCHAR(160),
    file_paths_json TEXT,
    introduced_pr_number INT,
    resolved_pr_number INT,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    external_event_key VARCHAR(180),
    reported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_defects_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX idx_defects_tenant_repo ON defect_records(tenant_id, repository_id, reported_at DESC);
CREATE UNIQUE INDEX uq_defects_tenant_ext_key ON defect_records(tenant_id, external_event_key);

CREATE TABLE prediction_runs (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    repository_id VARCHAR(36) NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    review_id VARCHAR(36) NOT NULL REFERENCES pull_request_reviews(id) ON DELETE CASCADE,
    pr_number INT NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    engine_version VARCHAR(48) NOT NULL,
    model_type VARCHAR(48) NOT NULL,
    overall_engineering_risk_score INT NOT NULL,
    overall_risk_level VARCHAR(24) NOT NULL,
    signal_breakdown_json TEXT NOT NULL,
    feature_snapshot_json TEXT NOT NULL,
    historical_sample_size INT NOT NULL DEFAULT 0,
    data_freshness_at TIMESTAMP WITH TIME ZONE,
    data_availability_summary TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_pred_runs_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX idx_pred_runs_tenant_review ON prediction_runs(tenant_id, review_id, created_at DESC);
CREATE INDEX idx_pred_runs_tenant_repo ON prediction_runs(tenant_id, repository_id, created_at DESC);

CREATE TABLE prediction_items (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    prediction_run_id VARCHAR(36) NOT NULL REFERENCES prediction_runs(id) ON DELETE CASCADE,
    category VARCHAR(40) NOT NULL,
    risk_level VARCHAR(24) NOT NULL,
    risk_score INT NOT NULL,
    probability DECIMAL(5,4),
    confidence_level VARCHAR(24) NOT NULL,
    insufficient_data BOOLEAN NOT NULL DEFAULT FALSE,
    historical_sample_size INT NOT NULL DEFAULT 0,
    data_freshness_at TIMESTAMP WITH TIME ZONE,
    rule_or_model_version VARCHAR(48) NOT NULL,
    explanation TEXT NOT NULL,
    limitations TEXT NOT NULL,
    recommended_action TEXT NOT NULL,
    target_breakdown_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_pred_items_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX idx_pred_items_run ON prediction_items(tenant_id, prediction_run_id);

CREATE TABLE prediction_evidence (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    prediction_item_id VARCHAR(36) NOT NULL REFERENCES prediction_items(id) ON DELETE CASCADE,
    signal_source VARCHAR(40) NOT NULL,
    evidence_type VARCHAR(48) NOT NULL,
    target_ref VARCHAR(300),
    summary TEXT NOT NULL,
    weight INT NOT NULL DEFAULT 0,
    historical_count INT NOT NULL DEFAULT 0,
    observed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_pred_evidence_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX idx_pred_evidence_item ON prediction_evidence(tenant_id, prediction_item_id);

CREATE TABLE prediction_model_evaluations (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    repository_id VARCHAR(36) REFERENCES repositories(id) ON DELETE CASCADE,
    engine_version VARCHAR(48) NOT NULL,
    category VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    sample_size INT NOT NULL DEFAULT 0,
    positive_outcomes INT NOT NULL DEFAULT 0,
    precision_score DECIMAL(5,4),
    recall_score DECIMAL(5,4),
    f1_score DECIMAL(5,4),
    false_positive_rate DECIMAL(5,4),
    false_negative_rate DECIMAL(5,4),
    calibration_error DECIMAL(5,4),
    baseline_comparison_json TEXT,
    notes TEXT NOT NULL,
    evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_pred_evals_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX idx_pred_evals_tenant ON prediction_model_evaluations(tenant_id, evaluated_at DESC);
