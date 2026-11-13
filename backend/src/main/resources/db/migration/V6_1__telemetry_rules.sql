-- Regras configuráveis de monitoramento automático OpenTelemetry (SLO de taxa de erro e latência p95 por serviço).
CREATE TABLE telemetry_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    service VARCHAR(200) NOT NULL,
    window_seconds INTEGER NOT NULL DEFAULT 90 CHECK (window_seconds BETWEEN 30 AND 3600),
    max_error_rate_percent DOUBLE PRECISION NULL CHECK (max_error_rate_percent IS NULL OR (max_error_rate_percent > 0 AND max_error_rate_percent <= 100)),
    min_requests INTEGER NOT NULL DEFAULT 3 CHECK (min_requests >= 1),
    max_p95_latency_ms DOUBLE PRECISION NULL CHECK (max_p95_latency_ms IS NULL OR max_p95_latency_ms > 0),
    severity VARCHAR(10) NOT NULL DEFAULT 'SEV2' CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_telemetry_rules_org_service UNIQUE (organization_id, service)
);

CREATE INDEX idx_telemetry_rules_org ON telemetry_rules (organization_id);
