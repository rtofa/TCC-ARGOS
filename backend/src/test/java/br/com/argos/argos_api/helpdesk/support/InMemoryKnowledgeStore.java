package br.com.argos.argos_api.helpdesk.support;

import br.com.argos.argos_api.helpdesk.guardrail.PortugueseText;
import br.com.argos.argos_api.helpdesk.ingestion.MarkdownChunker;
import br.com.argos.argos_api.helpdesk.model.ChunkedDocument;
import br.com.argos.argos_api.helpdesk.model.KnowledgeChunk;
import br.com.argos.argos_api.helpdesk.model.ParentSection;
import br.com.argos.argos_api.helpdesk.model.ScoredChunk;
import br.com.argos.argos_api.helpdesk.retrieval.TextEmbedder;
import br.com.argos.argos_api.helpdesk.store.KnowledgeStore;
import br.com.argos.argos_api.helpdesk.store.KnowledgeStoreException;
import br.com.argos.argos_api.helpdesk.store.StoredDocument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Knowledge store in memory for unit tests, mimicking the PostgreSQL one:
 *
 * <ul>
 *   <li>hybrid search: vector ranking (cosine) and keyword ranking (every query word present, like
 *       {@code plainto_tsquery}) fused with RRF (k = 60);</li>
 *   <li>lexical search over the parents: number of query words found, any word matching.</li>
 * </ul>
 */
public class InMemoryKnowledgeStore implements KnowledgeStore {

    private static final int RRF_K = 60;

    private final Map<String, KnowledgeChunk> chunks = new LinkedHashMap<>();
    private final Map<String, float[]> vectors = new LinkedHashMap<>();
    private final Map<String, ParentSection> parents = new LinkedHashMap<>();
    private final Map<String, StoredDocument> documents = new LinkedHashMap<>();
    public final AtomicInteger hybridSearches = new AtomicInteger();
    public final AtomicInteger lexicalSearches = new AtomicInteger();
    public volatile boolean down;
    private volatile boolean semantic;

    /** Chunks and indexes the given Markdown documents ({@code name -> content}). */
    public static InMemoryKnowledgeStore indexed(MarkdownChunker chunker, TextEmbedder embedder, Map<String, String> files) {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();
        store.prepare(embedder == null ? 8 : embedder.dimensions(), embedder != null && embedder.available());
        files.forEach((name, content) -> {
            ChunkedDocument document = chunker.chunk(new MarkdownChunker.SourceFile(name, content));
            List<float[]> embeddings = store.semantic
                    ? embedder.embed(document.chunks().stream().map(KnowledgeChunk::contextualText).toList()) : null;
            store.replaceDocument(new StoredDocument(document.docId(), document.title(), name, "h", document.parents().size(),
                    document.chunks().size(), embeddings != null, null, "2026-11-20T00:00:00Z"),
                    document.parents(), document.chunks(), embeddings);
        });
        return store;
    }

    @Override
    public boolean prepare(int dimensions, boolean semantic) {
        this.semantic = semantic;
        return false;
    }

    @Override
    public boolean hybridReady() {
        return semantic;
    }

    @Override
    public Map<String, StoredDocument> documents() {
        return Map.copyOf(documents);
    }

    @Override
    public synchronized void replaceDocument(StoredDocument document, List<ParentSection> parentSections,
                                             List<KnowledgeChunk> newChunks, List<float[]> embeddings) {
        deleteDocuments(List.of(document.docId()));
        parentSections.forEach(parent -> parents.put(parent.parentId(), parent));
        if (embeddings != null) {
            for (int i = 0; i < newChunks.size(); i++) {
                chunks.put(newChunks.get(i).chunkId(), newChunks.get(i));
                vectors.put(newChunks.get(i).chunkId(), embeddings.get(i));
            }
        }
        documents.put(document.docId(), document);
    }

    @Override
    public synchronized void deleteDocuments(Collection<String> docIds) {
        chunks.values().removeIf(chunk -> docIds.contains(chunk.docId()));
        vectors.keySet().retainAll(chunks.keySet());
        parents.values().removeIf(parent -> docIds.contains(parent.docId()));
        docIds.forEach(documents::remove);
    }

    @Override
    public synchronized List<ScoredChunk> hybridSearch(String text, float[] vector, int topK) {
        check();
        hybridSearches.incrementAndGet();
        List<KnowledgeChunk> byVector = chunks.values().stream()
                .sorted(Comparator.comparingDouble((KnowledgeChunk c) -> cosine(vector, vectors.get(c.chunkId()))).reversed())
                .limit(topK).toList();
        Set<String> terms = new HashSet<>(PortugueseText.contentStems(text));
        List<KnowledgeChunk> byKeyword = terms.isEmpty() ? List.of() : chunks.values().stream()
                .filter(c -> new HashSet<>(PortugueseText.contentStems(c.contextualText())).containsAll(terms))
                .limit(topK).toList();
        Map<String, Double> fused = new LinkedHashMap<>();
        for (int i = 0; i < byVector.size(); i++) {
            fused.merge(byVector.get(i).chunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < byKeyword.size(); i++) {
            fused.merge(byKeyword.get(i).chunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
        List<ScoredChunk> results = new ArrayList<>();
        fused.forEach((id, score) -> results.add(new ScoredChunk(chunks.get(id), score, cosine(vector, vectors.get(id)))));
        results.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return results.subList(0, Math.min(topK, results.size()));
    }

    @Override
    public synchronized List<ScoredChunk> lexicalSearch(String query, int topK) {
        check();
        lexicalSearches.incrementAndGet();
        Set<String> terms = new HashSet<>(PortugueseText.contentStems(query));
        return parents.values().stream()
                .map(parent -> {
                    String text = MarkdownChunker.contextual(parent.docTitle(), parent.sectionPath(), parent.content());
                    Set<String> stems = new HashSet<>(PortugueseText.contentStems(text));
                    long matched = terms.stream().filter(stems::contains).count();
                    KnowledgeChunk asChunk = new KnowledgeChunk(parent.parentId(), parent.parentId(), parent.docId(),
                            parent.docTitle(), parent.sectionPath(), parent.source(), parent.content(), text,
                            parent.position());
                    return new ScoredChunk(asChunk, matched);
                })
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    @Override
    public synchronized Map<String, ParentSection> parents(Collection<String> parentIds) {
        check();
        Map<String, ParentSection> found = new LinkedHashMap<>();
        parentIds.forEach(id -> {
            if (parents.containsKey(id)) {
                found.put(id, parents.get(id));
            }
        });
        return found;
    }

    @Override
    public long chunkCount() {
        return chunks.size();
    }

    @Override
    public boolean available() {
        return !down;
    }

    private void check() {
        if (down) {
            throw new KnowledgeStoreException("fora do ar", new IllegalStateException("down"));
        }
    }

    private static double cosine(float[] a, float[] b) {
        if (b == null) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
}
