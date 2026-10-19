-- SD-01: deploys reported by CI/CD pipelines through an API key.
CREATE TABLE deploys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    service VARCHAR(100) NOT NULL,
    version VARCHAR(100) NOT NULL,
    environment VARCHAR(50) NOT NULL,
    commit_sha VARCHAR(64) NULL,
    author VARCHAR(200) NULL,
    url VARCHAR(500) NULL,
    description VARCHAR(2000) NULL,
    deployed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    api_key_id UUID NOT NULL REFERENCES api_keys(id)
);

CREATE INDEX idx_deploys_org_deployed_at ON deploys (organization_id, deployed_at DESC);
CREATE INDEX idx_deploys_org_service_deployed_at ON deploys (organization_id, service, deployed_at DESC);
