package br.com.argos.argos_api.helpdesk.store;

import br.com.argos.argos_api.helpdesk.HelpdeskProperties;
import br.com.argos.argos_api.helpdesk.ingestion.MarkdownChunker;
import br.com.argos.argos_api.helpdesk.model.KnowledgeChunk;
import br.com.argos.argos_api.helpdesk.model.ParentSection;
import br.com.argos.argos_api.helpdesk.model.ScoredChunk;
import br.com.argos.argos_api.shared.resilience.CircuitBreaker;
import br.com.argos.argos_api.shared.resilience.CircuitOpenException;
import br.com.argos.argos_api.shared.resilience.ResilientCall;
import br.com.argos.argos_api.shared.resilience.RetryPolicy;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Knowledge base on PostgreSQL.
 *
 * <ul>
 *   <li><b>Children + embeddings</b>: LangChain4j's {@link PgVectorEmbeddingStore} in {@code HYBRID} search mode. One
 *       SQL query runs a vector search (pgvector, cosine distance) and a full-text search (Portuguese dictionary,
 *       {@code ts_rank}) and fuses both rankings with Reciprocal Rank Fusion. The table is created by LangChain4j
 *       only when embeddings are configured, so the rest of Argos runs on plain PostgreSQL.</li>
 *   <li><b>Parents and documents</b>: regular tables (Flyway V13). Parents also have a weighted full-text index,
 *       which keeps the agent working, by full-text alone, while embeddings are off.</li>
 * </ul>
 *
 * Every call goes through a circuit breaker with retries for transient database failures.
 */
@Component
public class PgVectorKnowledgeStore implements KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorKnowledgeStore.class);

    private static final String PARENT_COLUMNS = "parent_id, doc_id, doc_title, section_path, source, content, position";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final HelpdeskProperties.Store settings;
    private final int rrfK;
    private final ResilientCall resilience;
    private volatile PgVectorEmbeddingStore vectors;

    public PgVectorKnowledgeStore(DataSource dataSource, JdbcTemplate jdbc, HelpdeskProperties properties, Clock clock) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.settings = properties.getStore();
        this.rrfK = properties.getRetrieval().getRrfK();
        RetryPolicy retry = RetryPolicy.exponential(settings.getMaxAttempts(), settings.getRetryInitialDelay(),
                settings.getRetryMaxDelay());
        this.resilience = new ResilientCall("postgres-helpdesk", new CircuitBreaker(5, Duration.ofSeconds(30), clock),
                retry, PgVectorKnowledgeStore::isTransient);
    }

    // ------------------------------------------------------------------ preparation

    @Override
    public boolean prepare(int dimensions, boolean semantic) {
        if (!semantic) {
            vectors = null;
            return false;
        }
        String table = settings.getChunksTable();
        boolean rebuilt = call("verificar a tabela vetorial", () -> {
            String current = jdbc.query("""
                    SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a
                    WHERE a.attrelid = to_regclass(?) AND a.attname = 'embedding' AND NOT a.attisdropped""",
                    rs -> rs.next() ? rs.getString(1) : null, table);
            if (current != null && !current.equals("vector(" + dimensions + ")")) {
                // Another embedding size: the vectors are useless, so the documents are indexed again.
                log.warn("Tabela {} tem {} e o modelo usa vector({}); recriando", table, current, dimensions);
                jdbc.execute("DROP TABLE " + table);
                jdbc.update("DELETE FROM helpdesk_documents");
                return true;
            }
            return false;
        });
        try {
            vectors = PgVectorEmbeddingStore.datasourceBuilder()
                    .datasource(dataSource)
                    .table(table)
                    .dimension(dimensions)
                    .createTable(true)
                    .useIndex(false) // exact search: precise and fast enough for documentation-sized collections
                    .searchMode(PgVectorEmbeddingStore.SearchMode.HYBRID)
                    .textSearchConfig(settings.getTextSearchConfig())
                    .rrfK(rrfK)
                    .metadataStorageConfig(DefaultMetadataStorageConfig.defaultConfig())
                    .build();
        } catch (RuntimeException e) {
            // Usually the pgvector extension is missing: stay on full-text search instead of failing.
            vectors = null;
            log.warn("Busca híbrida indisponível (pgvector): {}. Seguindo só com busca textual.", e.getMessage());
        }
        return rebuilt;
    }

    @Override
    public boolean hybridReady() {
        return vectors != null;
    }

    // ------------------------------------------------------------------ writes

    @Override
    public Map<String, StoredDocument> documents() {
        return call("listar documentos", () -> {
            Map<String, StoredDocument> documents = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT doc_id, title, source, hash, parents, chunks, embedded, embedding_model, indexed_at
                    FROM helpdesk_documents ORDER BY source""", rs -> {
                documents.put(rs.getString("doc_id"), new StoredDocument(rs.getString("doc_id"), rs.getString("title"),
                        rs.getString("source"), rs.getString("hash"), rs.getInt("parents"), rs.getInt("chunks"),
                        rs.getBoolean("embedded"), rs.getString("embedding_model"),
                        rs.getTimestamp("indexed_at").toInstant().toString()));
            });
            return documents;
        });
    }

    @Override
    public void replaceDocument(StoredDocument document, List<ParentSection> parents, List<KnowledgeChunk> chunks,
                                List<float[]> embeddings) {
        if (embeddings != null && embeddings.size() != chunks.size()) {
            throw new IllegalArgumentException("Um embedding por chunk é obrigatório");
        }
        PgVectorEmbeddingStore store = vectors;
        if (embeddings != null && store == null) {
            throw new IllegalStateException("Tabela vetorial não preparada");
        }
        call("indexar " + document.source(), () -> {
            removeChunks(List.of(document.docId()));
            jdbc.update("DELETE FROM helpdesk_parents WHERE doc_id = ?", document.docId());
            jdbc.batchUpdate("INSERT INTO helpdesk_parents (" + PARENT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                    parents, 200, (ps, parent) -> {
                        ps.setString(1, parent.parentId());
                        ps.setString(2, parent.docId());
                        ps.setString(3, parent.docTitle());
                        ps.setString(4, parent.sectionPath());
                        ps.setString(5, parent.source());
                        ps.setString(6, parent.content());
                        ps.setInt(7, parent.position());
                    });
            if (embeddings != null && !chunks.isEmpty()) {
                List<String> ids = chunks.stream().map(chunk -> embeddingId(chunk.chunkId())).toList();
                List<Embedding> vectorsOfChunks = embeddings.stream().map(Embedding::from).toList();
                List<TextSegment> segments = chunks.stream().map(PgVectorKnowledgeStore::segment).toList();
                store.addAll(ids, vectorsOfChunks, segments);
            }
            // The document row goes last: if anything above fails, its fingerprint is not updated and the next run
            // indexes it again.
            jdbc.update("""
                    INSERT INTO helpdesk_documents (doc_id, title, source, hash, parents, chunks, embedded, embedding_model, indexed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (doc_id) DO UPDATE SET title = EXCLUDED.title, source = EXCLUDED.source,
                        hash = EXCLUDED.hash, parents = EXCLUDED.parents, chunks = EXCLUDED.chunks,
                        embedded = EXCLUDED.embedded, embedding_model = EXCLUDED.embedding_model,
                        indexed_at = EXCLUDED.indexed_at""",
                    document.docId(), document.title(), document.source(), document.hash(), document.parents(),
                    document.chunks(), document.embedded(), document.embeddingModel(),
                    Timestamp.from(Instant.parse(document.indexedAt())));
            return null;
        });
    }

    @Override
    public void deleteDocuments(Collection<String> docIds) {
        if (docIds.isEmpty()) {
            return;
        }
        call("remover documentos", () -> {
            removeChunks(docIds);
            String placeholders = placeholders(docIds.size());
            jdbc.update("DELETE FROM helpdesk_parents WHERE doc_id IN (" + placeholders + ")", docIds.toArray());
            jdbc.update("DELETE FROM helpdesk_documents WHERE doc_id IN (" + placeholders + ")", docIds.toArray());
            return null;
        });
    }

    private void removeChunks(Collection<String> docIds) {
        PgVectorEmbeddingStore store = vectors;
        if (store != null) {
            store.removeAll(metadataKey("doc_id").isIn(docIds));
        } else if (vectorTableExists()) {
            // Embeddings are off now, but chunks indexed earlier must not survive their document.
            jdbc.update("DELETE FROM " + settings.getChunksTable() + " WHERE metadata->>'doc_id' IN ("
                    + placeholders(docIds.size()) + ")", docIds.toArray());
        }
    }

    // ------------------------------------------------------------------ reads

    @Override
    public List<ScoredChunk> hybridSearch(String text, float[] vector, int topK) {
        PgVectorEmbeddingStore store = vectors;
        if (store == null) {
            throw new IllegalStateException("Busca híbrida indisponível");
        }
        List<EmbeddingMatch<TextSegment>> matches = call("busca híbrida", () -> store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(vector))
                .query(text)
                .maxResults(topK)
                .minScore(0.0)
                .build()).matches());
        List<ScoredChunk> results = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> match : matches) {
            if (match.embedded() == null) {
                continue;
            }
            Double similarity = match.embedding() == null ? null : cosine(vector, match.embedding().vector());
            results.add(new ScoredChunk(chunk(match.embedded()), match.score() == null ? 0 : match.score(), similarity));
        }
        return results;
    }

    @Override
    public List<ScoredChunk> lexicalSearch(String query, int topK) {
        String anyWord = anyWordQuery(query);
        if (anyWord.isEmpty()) {
            return List.of();
        }
        return call("busca textual", () -> jdbc.query("""
                SELECT %s, ts_rank_cd(p.search_vector, q, 32) AS score
                FROM helpdesk_parents p, websearch_to_tsquery('portuguese', ?) q
                WHERE p.search_vector @@ q
                ORDER BY score DESC, p.position
                LIMIT ?""".formatted(prefixed()), (rs, row) -> {
            ParentSection parent = PARENT_MAPPER.mapRow(rs, row);
            KnowledgeChunk asChunk = new KnowledgeChunk(parent.parentId(), parent.parentId(), parent.docId(),
                    parent.docTitle(), parent.sectionPath(), parent.source(), parent.content(),
                    MarkdownChunker.contextual(parent.docTitle(), parent.sectionPath(), parent.content()),
                    parent.position());
            return new ScoredChunk(asChunk, rs.getDouble("score"));
        }, anyWord, topK));
    }

    @Override
    public Map<String, ParentSection> parents(Collection<String> parentIds) {
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        return call("buscar seções", () -> {
            Map<String, ParentSection> parents = new LinkedHashMap<>();
            jdbc.query("SELECT " + prefixed() + " FROM helpdesk_parents p WHERE p.parent_id IN ("
                            + placeholders(parentIds.size()) + ")",
                    rs -> {
                        ParentSection parent = PARENT_MAPPER.mapRow(rs, 0);
                        parents.put(parent.parentId(), parent);
                    }, parentIds.toArray());
            return parents;
        });
    }

    @Override
    public long chunkCount() {
        return call("contar chunks", () -> vectorTableExists()
                ? jdbc.queryForObject("SELECT count(*) FROM " + settings.getChunksTable(), Long.class)
                : 0L);
    }

    @Override
    public boolean available() {
        try {
            return jdbc.queryForObject("SELECT 1", Integer.class) == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ mapping

    private static final RowMapper<ParentSection> PARENT_MAPPER = (rs, row) -> new ParentSection(
            rs.getString("parent_id"), rs.getString("doc_id"), rs.getString("doc_title"), rs.getString("section_path"),
            rs.getString("source"), rs.getString("content"), rs.getInt("position"));

    /** The text is what both searches see (embedded and full-text indexed); the rest rebuilds the chunk. */
    private static TextSegment segment(KnowledgeChunk chunk) {
        Metadata metadata = new Metadata()
                .put("chunk_id", chunk.chunkId())
                .put("parent_id", chunk.parentId())
                .put("doc_id", chunk.docId())
                .put("doc_title", chunk.docTitle())
                .put("section_path", chunk.sectionPath())
                .put("source", chunk.source())
                .put("body", chunk.body())
                .put("position", chunk.position());
        return TextSegment.from(chunk.contextualText(), metadata);
    }

    private static KnowledgeChunk chunk(TextSegment segment) {
        Metadata metadata = segment.metadata();
        Integer position = metadata.getInteger("position");
        String sectionPath = metadata.getString("section_path");
        return new KnowledgeChunk(metadata.getString("chunk_id"), metadata.getString("parent_id"),
                metadata.getString("doc_id"), metadata.getString("doc_title"), sectionPath == null ? "" : sectionPath,
                metadata.getString("source"), metadata.getString("body"), segment.text(), position == null ? 0 : position);
    }

    /** pgvector rows are keyed by UUID; derived from the chunk id so re-indexing overwrites the same rows. */
    private static String embeddingId(String chunkId) {
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** "como configuro o Slack" -> "como or configuro or slack": a section matching any word is a candidate. */
    static String anyWordQuery(String query) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : query.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && !word.equals("or")) {
                words.add(word);
            }
        }
        return String.join(" or ", words);
    }

    private static String prefixed() {
        return "p.parent_id, p.doc_id, p.doc_title, p.section_path, p.source, p.content, p.position";
    }

    private boolean vectorTableExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                settings.getChunksTable()));
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / Math.sqrt(normA * normB);
    }

    // ------------------------------------------------------------------ resilience

    private <T> T call(String operation, Supplier<T> action) {
        try {
            return resilience.call(action);
        } catch (CircuitOpenException e) {
            throw new KnowledgeStoreException("Base de conhecimento indisponível (" + operation + ")", e);
        } catch (RuntimeException e) {
            throw new KnowledgeStoreException("Falha ao " + operation + " na base de conhecimento: " + e.getMessage(), e);
        }
    }

    /** Lost connections, overload and serialization conflicts are worth retrying; SQL errors are not. */
    static boolean isTransient(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof DataAccessResourceFailureException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                return state.startsWith("08") || state.startsWith("40") || state.startsWith("53") || state.startsWith("57P");
            }
        }
        return false;
    }
}
