CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Enable Row Level Security on all tables that belong to a tenant
ALTER TABLE users ENABLE ROW LEVEL SECURITY;

-- Create policy for users based on the current tenant setting
CREATE POLICY tenant_isolation_policy ON users
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);
