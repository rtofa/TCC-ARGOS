-- P0-1: telemetry (traces, logs, metrics) received over OTLP/HTTP through an API key.
-- Every row carries the organization of the API key that sent it.

CREATE TABLE telemetry_spans (
    id BIGSERIAL PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    trace_id VARCHAR(32) NOT NULL,
    span_id VARCHAR(16) NOT NULL,
    parent_span_id VARCHAR(16) NULL,
    service VARCHAR(200) NOT NULL,
    name VARCHAR(500) NOT NULL,
    kind SMALLINT NOT NULL DEFAULT 0,
    start_time TIMESTAMPTZ NOT NULL,
    duration_ns BIGINT NOT NULL,
    status_code SMALLINT NOT NULL DEFAULT 0,
    status_message VARCHAR(1000) NULL,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    received_at TIMESTAMPTZ NOT NULL
);

-- The collector retries failed exports: re-sending a span must not duplicate it.
CREATE UNIQUE INDEX uq_telemetry_spans_span ON telemetry_spans (organization_id, trace_id, span_id);
CREATE INDEX idx_telemetry_spans_org_time ON telemetry_spans (organization_id, start_time DESC);
CREATE INDEX idx_telemetry_spans_org_service_time ON telemetry_spans (organization_id, service, start_time DESC);

CREATE TABLE telemetry_logs (
    id BIGSERIAL PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    service VARCHAR(200) NOT NULL,
    severity_number SMALLINT NOT NULL DEFAULT 0,
    severity_text VARCHAR(20) NULL,
    body TEXT NOT NULL,
    trace_id VARCHAR(32) NULL,
    span_id VARCHAR(16) NULL,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    received_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_telemetry_logs_org_time ON telemetry_logs (organization_id, occurred_at DESC);
CREATE INDEX idx_telemetry_logs_org_service_time ON telemetry_logs (organization_id, service, occurred_at DESC);
CREATE INDEX idx_telemetry_logs_org_trace ON telemetry_logs (organization_id, trace_id) WHERE trace_id IS NOT NULL;

CREATE TABLE telemetry_metrics (
    id BIGSERIAL PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    service VARCHAR(200) NOT NULL,
    name VARCHAR(300) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    unit VARCHAR(50) NULL,
    value DOUBLE PRECISION NOT NULL,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    received_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_telemetry_metrics_org_name_time ON telemetry_metrics (organization_id, name, service, occurred_at DESC);
