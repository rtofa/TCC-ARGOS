-- AI-01 / AI-02 / AI-05: investigação automática de incidentes por agente de IA, com memória conversacional.

-- Cada linha é uma investigação e também um item da fila de trabalho: o poller reivindica as linhas PENDING
-- com run_after vencido (FOR UPDATE SKIP LOCKED), então a fila sobrevive a reinícios e a várias instâncias.
CREATE TABLE ai_investigations (
    id                 UUID PRIMARY KEY,
    organization_id    UUID         NOT NULL REFERENCES organizations (id),
    incident_id        UUID         NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    status             VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    trigger            VARCHAR(16)  NOT NULL CHECK (trigger IN ('AUTO', 'MANUAL')),
    mode               VARCHAR(16)  NULL CHECK (mode IN ('AI', 'HEURISTIC')),
    requested_by       UUID         NULL,
    attempts           INTEGER      NOT NULL DEFAULT 0,
    run_after          TIMESTAMPTZ  NOT NULL,
    report             JSONB        NULL,
    steps              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    guardrail_events   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    llm_calls          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    model              VARCHAR(80)  NULL,
    fallback_used      BOOLEAN      NOT NULL DEFAULT FALSE,
    input_tokens       INTEGER      NOT NULL DEFAULT 0,
    output_tokens      INTEGER      NOT NULL DEFAULT 0,
    cache_read_tokens  INTEGER      NOT NULL DEFAULT 0,
    cache_write_tokens INTEGER      NOT NULL DEFAULT 0,
    cost_usd           NUMERIC(12, 6) NOT NULL DEFAULT 0,
    tool_calls         INTEGER      NOT NULL DEFAULT 0,
    error              TEXT         NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at         TIMESTAMPTZ  NULL,
    finished_at        TIMESTAMPTZ  NULL
);

CREATE INDEX idx_ai_investigations_incident ON ai_investigations (organization_id, incident_id, created_at DESC);
CREATE INDEX idx_ai_investigations_queue ON ai_investigations (run_after) WHERE status = 'PENDING';
-- No máximo uma investigação ativa por incidente (idempotência dos gatilhos automático e manual).
CREATE UNIQUE INDEX uq_ai_investigations_active ON ai_investigations (incident_id) WHERE status IN ('PENDING', 'RUNNING');

-- Memória de curto prazo do assistente (janela de mensagens da LangChain4j serializada em JSON).
CREATE TABLE ai_chat_memory (
    memory_id       VARCHAR(120) PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations (id),
    messages        JSONB        NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Histórico completo exibido na tela (a memória do modelo é só uma janela dele).
CREATE TABLE ai_chat_messages (
    id                UUID PRIMARY KEY,
    organization_id   UUID         NOT NULL REFERENCES organizations (id),
    incident_id       UUID         NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    role              VARCHAR(16)  NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    author_id         UUID         NULL,
    content           TEXT         NOT NULL DEFAULT '',
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    model             VARCHAR(80)  NULL,
    input_tokens      INTEGER      NOT NULL DEFAULT 0,
    output_tokens     INTEGER      NOT NULL DEFAULT 0,
    cache_read_tokens INTEGER      NOT NULL DEFAULT 0,
    guardrail         VARCHAR(40)  NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    answered_at       TIMESTAMPTZ  NULL
);

CREATE INDEX idx_ai_chat_messages_incident ON ai_chat_messages (organization_id, incident_id, created_at);
-- Uma pergunta por vez em cada incidente, para a memória da conversa seguir a ordem das perguntas.
CREATE UNIQUE INDEX uq_ai_chat_messages_pending ON ai_chat_messages (incident_id) WHERE status = 'PENDING';

-- RLS no mesmo padrão da V7. Os jobs de sistema (poller e watchdog) atravessam organizações por natureza,
-- então se identificam com app.system_jobs = 'on' dentro da própria transação.
ALTER TABLE ai_investigations ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_investigations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON ai_investigations
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid
           OR current_setting('app.system_jobs', TRUE) = 'on')
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid
           OR current_setting('app.system_jobs', TRUE) = 'on');

ALTER TABLE ai_chat_memory ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_chat_memory FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON ai_chat_memory
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid);

ALTER TABLE ai_chat_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_chat_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON ai_chat_messages
    USING (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid
           OR current_setting('app.system_jobs', TRUE) = 'on')
    WITH CHECK (organization_id = NULLIF(current_setting('app.current_tenant', TRUE), '')::uuid
           OR current_setting('app.system_jobs', TRUE) = 'on');

GRANT SELECT, INSERT, UPDATE, DELETE ON ai_investigations, ai_chat_memory, ai_chat_messages TO argos_tenant;
