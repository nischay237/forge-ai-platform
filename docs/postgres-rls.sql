-- Optional PostgreSQL 16 Row-Level Security (RLS) Hardening Script
-- Apply manually on PostgreSQL production databases when using a non-superuser application role
-- that sets `SET LOCAL app.current_tenant_id = '<tenant-uuid>'` per request/worker transaction.
-- NOTE: Kept separate from Flyway migrations so H2 PostgreSQL-compatibility test databases remain portable.

DO $$
DECLARE
    tbl TEXT;
    tenant_tables TEXT[] := ARRAY[
        'users',
        'refresh_tokens',
        'repositories',
        'source_files',
        'code_symbols',
        'code_dependencies',
        'pull_request_reviews',
        'review_findings',
        'security_findings',
        'finding_suppressions',
        'change_risk_policies',
        'ci_builds',
        'audit_logs',
        'evaluation_runs',
        'outbox_events',
        'processed_events',
        'engineering_events',
        'historical_test_executions',
        'deployment_records',
        'incident_records',
        'defect_records',
        'prediction_runs',
        'prediction_items',
        'prediction_evidence',
        'prediction_model_evaluations'
    ];
BEGIN
    FOREACH tbl IN ARRAY tenant_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('DROP POLICY IF EXISTS forge_tenant_isolation ON %I', tbl);
        EXECUTE format(
            'CREATE POLICY forge_tenant_isolation ON %I USING (tenant_id = current_setting(''app.current_tenant_id'', true)) WITH CHECK (tenant_id = current_setting(''app.current_tenant_id'', true))',
            tbl
        );
    END LOOP;
END $$;
