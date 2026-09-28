CREATE TABLE incidents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    title VARCHAR(200) NOT NULL,
    description TEXT NULL,
    severity VARCHAR(10) NOT NULL CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'INVESTIGATING', 'MITIGATED', 'RESOLVED')),
    source VARCHAR(20) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'ALERT')),
    affected_services TEXT[] NOT NULL DEFAULT '{}',
    assignee_id UUID NULL REFERENCES users(id),
    created_by UUID NOT NULL REFERENCES users(id),
    opened_at TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ NULL,
    resolved_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_incidents_org_opened_at ON incidents (organization_id, opened_at DESC);
CREATE INDEX idx_incidents_org_status ON incidents (organization_id, status);

-- Append-only timeline: status/severity/assignee changes and comments.
CREATE TABLE incident_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seq BIGINT GENERATED ALWAYS AS IDENTITY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    incident_id UUID NOT NULL REFERENCES incidents(id),
    type VARCHAR(30) NOT NULL,
    actor_id UUID NOT NULL REFERENCES users(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    old_value VARCHAR(100) NULL,
    new_value VARCHAR(100) NULL,
    body TEXT NULL
);

CREATE INDEX idx_incident_events_timeline ON incident_events (incident_id, occurred_at, seq);
