-- Knowledge base of the support agent: the official documentation of the platform, shared by every organization
-- (not tenant data, so no row-level security).
--
-- The child chunks and their embeddings live in the pgvector table that LangChain4j creates (helpdesk_chunks), only
-- when an embedding model is configured. These two tables work on plain PostgreSQL: without embeddings the agent
-- still finds sections by full-text search.

CREATE TABLE helpdesk_documents (
    doc_id          VARCHAR(200) PRIMARY KEY,
    title           VARCHAR(300) NOT NULL,
    source          VARCHAR(500) NOT NULL,
    hash            VARCHAR(64)  NOT NULL,
    parents         INT          NOT NULL,
    chunks          INT          NOT NULL,
    embedded        BOOLEAN      NOT NULL,
    embedding_model VARCHAR(200),
    indexed_at      TIMESTAMPTZ  NOT NULL
);

CREATE TABLE helpdesk_parents (
    parent_id     VARCHAR(250) PRIMARY KEY,
    doc_id        VARCHAR(200) NOT NULL,
    doc_title     VARCHAR(300) NOT NULL,
    section_path  VARCHAR(1000) NOT NULL DEFAULT '',
    source        VARCHAR(500) NOT NULL,
    content       TEXT         NOT NULL,
    position      INT          NOT NULL,
    -- Title and headings weigh more than the body; the Portuguese dictionary handles stopwords and stemming.
    search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('portuguese', coalesce(doc_title, '') || ' ' || coalesce(section_path, '')), 'A') ||
        setweight(to_tsvector('portuguese', coalesce(content, '')), 'B')) STORED
);

CREATE INDEX helpdesk_parents_doc_idx ON helpdesk_parents (doc_id);
CREATE INDEX helpdesk_parents_search_idx ON helpdesk_parents USING gin (search_vector);
