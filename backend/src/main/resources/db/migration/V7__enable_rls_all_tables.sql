-- Quadro 2 / Seção 3.1.3: Isolamento Multi-Tenant completo via Row-Level Security (RLS) no PostgreSQL.
-- Ativa ENABLE ROW LEVEL SECURITY e FORCE ROW LEVEL SECURITY em todas as tabelas pertencentes a um tenant.

ALTER TABLE users FORCE ROW LEVEL SECURITY;

-- Incidents & Timeline (events + comments)
ALTER TABLE incidents ENABLE ROW LEVEL SECURITY;
ALTER TABLE incidents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON incidents
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

ALTER TABLE incident_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE incident_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON incident_events
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

-- API Keys & Alerting
ALTER TABLE api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON api_keys
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

ALTER TABLE alert_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert_sources FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON alert_sources
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

ALTER TABLE alert_incident_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert_incident_links FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON alert_incident_links
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

-- Deploys (CI/CD events)
ALTER TABLE deploys ENABLE ROW LEVEL SECURITY;
ALTER TABLE deploys FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON deploys
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

-- OpenTelemetry (spans, logs, metrics, monitor rules)
ALTER TABLE telemetry_spans ENABLE ROW LEVEL SECURITY;
ALTER TABLE telemetry_spans FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON telemetry_spans
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

ALTER TABLE telemetry_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE telemetry_logs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON telemetry_logs
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

ALTER TABLE telemetry_metrics ENABLE ROW LEVEL SECURITY;
ALTER TABLE telemetry_metrics FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON telemetry_metrics
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

ALTER TABLE telemetry_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE telemetry_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON telemetry_rules
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

-- Role sem privilégio de superuser (NOBYPASSRLS) para imposição estrita das políticas RLS no motor do PostgreSQL.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'argos_tenant') THEN
        CREATE ROLE argos_tenant NOLOGIN NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO argos_tenant;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO argos_tenant;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO argos_tenant;
