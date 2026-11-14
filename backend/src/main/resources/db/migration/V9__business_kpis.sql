-- NEG-01 / NEG-02: KPIs de negócio da organização e o quanto cada serviço pesa em cada KPI.
-- NEG-03 / NEG-04 (impacto do incidente e visão executiva) são calculados a partir destas tabelas.

CREATE TABLE business_kpis (
    id                  UUID PRIMARY KEY,
    organization_id     UUID           NOT NULL REFERENCES organizations (id),
    name                VARCHAR(80)    NOT NULL,
    description         VARCHAR(300)   NULL,
    unit                VARCHAR(30)    NOT NULL,
    -- Volume normal do KPI por minuto (ex.: 12 pedidos/min), a referência para estimar o que se perde.
    baseline_per_minute NUMERIC(14, 4) NOT NULL CHECK (baseline_per_minute >= 0),
    -- Valor de cada unidade em reais (ex.: ticket médio de R$ 180). Nulo quando o KPI não tem valor monetário.
    value_per_unit      NUMERIC(14, 2) NULL CHECK (value_per_unit IS NULL OR value_per_unit >= 0),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_business_kpis_org_name UNIQUE (organization_id, name)
);

CREATE TABLE business_service_kpis (
    id              UUID PRIMARY KEY,
    organization_id UUID          NOT NULL REFERENCES organizations (id),
    kpi_id          UUID          NOT NULL REFERENCES business_kpis (id) ON DELETE CASCADE,
    service         VARCHAR(200)  NOT NULL,
    -- Quanto do KPI se perde quando o serviço para totalmente (100 = o KPI zera sem ele).
    impact_percent  NUMERIC(5, 2) NOT NULL CHECK (impact_percent > 0 AND impact_percent <= 100),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_business_service_kpis UNIQUE (organization_id, kpi_id, service)
);

CREATE INDEX idx_business_service_kpis_service ON business_service_kpis (organization_id, lower(service));

ALTER TABLE business_kpis ENABLE ROW LEVEL SECURITY;
ALTER TABLE business_kpis FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON business_kpis
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

ALTER TABLE business_service_kpis ENABLE ROW LEVEL SECURITY;
ALTER TABLE business_service_kpis FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON business_service_kpis
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON business_kpis, business_service_kpis TO argos_tenant;
