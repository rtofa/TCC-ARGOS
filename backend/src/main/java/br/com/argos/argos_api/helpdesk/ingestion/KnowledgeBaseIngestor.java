package br.com.argos.argos_api.helpdesk.ingestion;

import br.com.argos.argos_api.helpdesk.ingestion.MarkdownChunker.SourceFile;
import br.com.argos.argos_api.helpdesk.model.ChunkedDocument;
import br.com.argos.argos_api.helpdesk.model.IngestionReport;
import br.com.argos.argos_api.helpdesk.model.KnowledgeChunk;
import br.com.argos.argos_api.helpdesk.retrieval.TextEmbedder;
import br.com.argos.argos_api.helpdesk.store.KnowledgeStore;
import br.com.argos.argos_api.helpdesk.store.StoredDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Indexing pipeline: load → chunk (parent-child) → embed → store.
 *
 * <p>Incremental: each document's fingerprint covers its content, the chunking settings and the embedding model, so
 * only new or changed documents are processed and documents removed from the source are removed from the index.
 * When embeddings fail the document is still indexed for full-text search and retried on the next run.
 */
public class KnowledgeBaseIngestor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseIngestor.class);

    /** Another indexing is running. */
    public static class IngestionInProgressException extends RuntimeException {
        public IngestionInProgressException() {
            super("A base de conhecimento já está sendo indexada");
        }
    }

    private final DocumentLoader loader;
    private final MarkdownChunker chunker;
    private final TextEmbedder embedder;
    private final KnowledgeStore store;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile IngestionReport lastReport;
    private volatile String lastError;

    public KnowledgeBaseIngestor(DocumentLoader loader, MarkdownChunker chunker, TextEmbedder embedder,
                                 KnowledgeStore store, Clock clock) {
        this.loader = loader;
        this.chunker = chunker;
        this.embedder = embedder;
        this.store = store;
        this.clock = clock;
    }

    public boolean running() {
        return lock.isLocked();
    }

    public IngestionReport lastReport() {
        return lastReport;
    }

    public String lastError() {
        return lastError;
    }

    /**
     * @param force re-index every document even when unchanged
     * @throws IngestionInProgressException when another indexing is running
     */
    public IngestionReport ingest(boolean force) {
        if (!lock.tryLock()) {
            throw new IngestionInProgressException();
        }
        long started = System.nanoTime();
        try {
            List<SourceFile> files = loader.load();
            boolean rebuilt = store.prepare(embedder.dimensions(), embedder.available());
            // Vectors only when there is a model to make them and a pgvector table to keep them.
            boolean semantic = embedder.available() && store.hybridReady();
            String vectorSpace = semantic ? embedder.fingerprint() : "none";
            Map<String, StoredDocument> indexed = rebuilt ? Map.of() : store.documents();
            List<String> warnings = new ArrayList<>();
            if (files.isEmpty()) {
                warnings.add("Nenhum documento .md encontrado em " + loader.location());
            }

            Set<String> current = new HashSet<>();
            int changed = 0;
            int unchanged = 0;
            int parents = 0;
            int chunks = 0;
            boolean allEmbedded = semantic;
            for (SourceFile file : files) {
                ChunkedDocument document = chunker.chunk(file);
                if (!current.add(document.docId())) {
                    warnings.add("Documento ignorado (id repetido): " + file.source());
                    continue;
                }
                parents += document.parents().size();
                chunks += document.chunks().size();
                String hash = fingerprint(file.content(), vectorSpace);
                StoredDocument previous = indexed.get(document.docId());
                if (!force && previous != null && hash.equals(previous.hash())) {
                    unchanged++;
                    allEmbedded &= previous.embedded();
                    continue;
                }
                List<float[]> vectors = semantic ? embed(document, warnings) : null;
                allEmbedded &= vectors != null;
                // Without vectors the fingerprint says so, and the next run retries the embeddings.
                String storedHash = vectors == null && semantic ? fingerprint(file.content(), "none") : hash;
                store.replaceDocument(new StoredDocument(document.docId(), document.title(), file.source(), storedHash,
                                document.parents().size(), document.chunks().size(), vectors != null,
                                vectors == null ? null : embedder.modelName(), Instant.now(clock).toString()),
                        document.parents(), document.chunks(), vectors);
                changed++;
            }

            Set<String> stale = new HashSet<>(indexed.keySet());
            stale.removeAll(current);
            store.deleteDocuments(stale);

            long durationMs = (System.nanoTime() - started) / 1_000_000;
            IngestionReport report = new IngestionReport(Instant.now(clock), durationMs, current.size(), changed,
                    unchanged, stale.size(), parents, chunks, allEmbedded, rebuilt, List.copyOf(warnings));
            lastReport = report;
            lastError = null;
            log.atInfo().setMessage("helpdesk.ingestion")
                    .addKeyValue("documents", report.documents())
                    .addKeyValue("indexed", report.indexed())
                    .addKeyValue("unchanged", report.unchanged())
                    .addKeyValue("removed", report.removed())
                    .addKeyValue("chunks", report.chunks())
                    .addKeyValue("embeddings", report.embeddings())
                    .addKeyValue("durationMs", durationMs)
                    .log();
            return report;
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            throw e;
        } finally {
            lock.unlock();
        }
    }

    private List<float[]> embed(ChunkedDocument document, List<String> warnings) {
        if (document.chunks().isEmpty()) {
            return List.of();
        }
        try {
            return embedder.embed(document.chunks().stream().map(KnowledgeChunk::contextualText).toList());
        } catch (RuntimeException e) {
            warnings.add("Embeddings indisponíveis para " + document.source() + " (indexado só para busca textual): " + e.getMessage());
            log.warn("Falha ao gerar embeddings de {}: {}", document.source(), e.getMessage());
            return null;
        }
    }

    private String fingerprint(String content, String embedding) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(content.getBytes(StandardCharsets.UTF_8));
            digest.update(("|" + chunker.signature() + "|" + embedding).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
