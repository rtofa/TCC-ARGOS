-- MON-08 / INT-05: Canais de notificação por organização (Slack, Discord e E-mail) acionados na abertura de incidentes.
CREATE TABLE notification_channels (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    name             VARCHAR(100) NOT NULL,
    type             VARCHAR(20)  NOT NULL CHECK (type IN ('SLACK', 'DISCORD', 'EMAIL')),
    target           VARCHAR(500) NOT NULL,
    min_severity     VARCHAR(10)  NOT NULL DEFAULT 'SEV3' CHECK (min_severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by       UUID         NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    last_notified_at TIMESTAMPTZ  NULL,
    last_error       VARCHAR(500) NULL
);

CREATE INDEX idx_notification_channels_org ON notification_channels (organization_id, created_at DESC);

ALTER TABLE notification_channels ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_channels FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON notification_channels
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON notification_channels TO argos_tenant;
