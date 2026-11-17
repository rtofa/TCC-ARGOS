-- Controle de usuários pelo ADMIN: status ativo/inativo, registro do último acesso e WITH CHECK na RLS.
ALTER TABLE users ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMPTZ NULL;

DROP POLICY IF EXISTS tenant_isolation_policy ON users;
CREATE POLICY tenant_isolation_policy ON users
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);
