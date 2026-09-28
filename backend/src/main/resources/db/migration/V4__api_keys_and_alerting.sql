-- BASE-03: API keys for external systems. Only the SHA-256 of the key is stored.
CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(100) NOT NULL,
    prefix VARCHAR(20) NOT NULL,
    key_hash VARCHAR(64) NOT NULL UNIQUE,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NULL,
    revoked_at TIMESTAMPTZ NULL
);

CREATE INDEX idx_api_keys_org_created_at ON api_keys (organization_id, created_at DESC);

-- INT-03: configurable alert sources (JSONPath mapping rules).
CREATE TABLE alert_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(100) NOT NULL,
    items_path VARCHAR(200) NULL,
    title_path VARCHAR(200) NOT NULL,
    dedup_key_path VARCHAR(200) NOT NULL,
    status_path VARCHAR(200) NULL,
    severity_path VARCHAR(200) NULL,
    service_path VARCHAR(200) NULL,
    description_path VARCHAR(200) NULL,
    status_map JSONB NOT NULL DEFAULT '{}',
    severity_map JSONB NOT NULL DEFAULT '{}',
    default_severity VARCHAR(10) NOT NULL DEFAULT 'SEV3'
        CHECK (default_severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ NULL
);

-- Deduplication: (source, dedup key) -> incident. source_id NULL = Argos default format.
CREATE TABLE alert_incident_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    source_id UUID NULL REFERENCES alert_sources(id),
    dedup_key VARCHAR(200) NOT NULL,
    incident_id UUID NOT NULL REFERENCES incidents(id),
    occurrences INTEGER NOT NULL DEFAULT 1,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ NULL
);

-- At most one open link per (organization, source, dedup key).
CREATE UNIQUE INDEX uq_alert_incident_links_open
    ON alert_incident_links (organization_id,
                             COALESCE(source_id, '00000000-0000-0000-0000-000000000000'::uuid),
                             dedup_key)
    WHERE closed_at IS NULL;
CREATE INDEX idx_alert_incident_links_incident ON alert_incident_links (incident_id);

-- MON-10: incidents and events created automatically have the system (NULL) as author.
ALTER TABLE incidents ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE incidents ADD CONSTRAINT chk_incidents_manual_has_creator
    CHECK (source = 'ALERT' OR created_by IS NOT NULL);

ALTER TABLE incident_events ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE incident_events ADD COLUMN api_key_id UUID NULL REFERENCES api_keys(id);
