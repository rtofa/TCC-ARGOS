-- Consultas de autenticação, feitas antes de se saber a organização: login por e-mail, verificação de e-mail único
-- e chave de API pelo hash. Com FORCE ROW LEVEL SECURITY, um usuário de banco que não é superusuário (o usuário
-- mestre do RDS, por exemplo) não enxerga nenhuma linha sem app.current_tenant. Estas políticas liberam só a
-- LEITURA, e só enquanto a aplicação liga app.auth_lookup dentro da transação (TenantSession.lookup).
-- Políticas permissivas se somam com OR à tenant_isolation_policy; escrita continua presa à organização.

CREATE POLICY auth_lookup_policy ON users
    FOR SELECT
    USING (current_setting('app.auth_lookup', TRUE) = 'on');

CREATE POLICY auth_lookup_policy ON api_keys
    FOR SELECT
    USING (current_setting('app.auth_lookup', TRUE) = 'on');
