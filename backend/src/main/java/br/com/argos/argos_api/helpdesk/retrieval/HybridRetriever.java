package br.com.argos.argos_api.helpdesk.retrieval;

import br.com.argos.argos_api.helpdesk.HelpdeskProperties;
import br.com.argos.argos_api.helpdesk.guardrail.PortugueseText;
import br.com.argos.argos_api.helpdesk.model.RetrievedPassage;
import br.com.argos.argos_api.helpdesk.model.ScoredChunk;
import br.com.argos.argos_api.helpdesk.observability.ExecutionTrace;
import br.com.argos.argos_api.helpdesk.observability.MdcPropagation;
import br.com.argos.argos_api.helpdesk.retrieval.ReciprocalRankFusion.Candidate;
import br.com.argos.argos_api.helpdesk.store.KnowledgeStore;
import br.com.argos.argos_api.helpdesk.store.KnowledgeStoreException;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Advanced RAG retrieval with hybrid search on PostgreSQL + pgvector:
 *
 * <ol>
 *   <li><b>Hybrid search</b> (LangChain4j {@code PgVectorEmbeddingStore}, {@code HYBRID} mode): for each query (the
 *       question and its reformulations, in parallel on virtual threads), one SQL query ranks the chunks by vector
 *       similarity (semantic) and by full-text relevance (lexical) and fuses both rankings with RRF.</li>
 *   <li><b>Fusion across queries</b>: RRF again over the result lists of the reformulations.</li>
 *   <li><b>Reranking</b>: a cross-encoder (Cohere Rerank) re-scores the best candidates and drops irrelevant ones.
 *       Without it, a relevance gate keeps chunks that share words with the question or are semantically close.</li>
 *   <li><b>Parent expansion</b>: the surviving children are replaced by their parent sections.</li>
 * </ol>
 *
 * Degrades instead of failing: without embeddings (or if they fail) the search runs on full-text alone; a reranker
 * outage means fused order plus the gate. Only an unreachable database is an error.
 *
 * <p>Also a LangChain4j {@link ContentRetriever}, so it can back a standard {@code RetrievalAugmentor}.
 */
public class HybridRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    public enum Mode { HYBRID, LEXICAL }

    public record Result(List<RetrievedPassage> passages, int candidates, Mode mode, boolean reranked) {
    }

    private final KnowledgeStore store;
    private final TextEmbedder embedder;
    private final Reranker reranker;
    private final ContextAssembler assembler;
    private final HelpdeskProperties.Retrieval settings;
    private final ExecutorService executor;

    public HybridRetriever(KnowledgeStore store, TextEmbedder embedder, Reranker reranker, ContextAssembler assembler,
                           HelpdeskProperties.Retrieval settings, ExecutorService executor) {
        this.store = store;
        this.embedder = embedder;
        this.reranker = reranker;
        this.assembler = assembler;
        this.settings = settings;
        this.executor = executor;
    }

    public boolean hybridAvailable() {
        return embedder.available() && store.hybridReady();
    }

    public Result retrieve(String question, List<String> queries, ExecutionTrace trace) {
        return retrieve(question, queries, trace, 1);
    }

    /**
     * @param firstIndex number given to the first passage, so passages found later (tool calls) keep counting
     * @throws KnowledgeBaseUnavailableException when the database cannot be reached
     */
    public Result retrieve(String question, List<String> queries, ExecutionTrace trace, int firstIndex) {
        List<String> searchQueries = searchQueries(question, queries);
        try {
            Mode mode = Mode.LEXICAL;
            List<List<ScoredChunk>> rankings = hybridAvailable() ? hybrid(searchQueries, trace) : null;
            if (rankings != null) {
                mode = Mode.HYBRID;
            } else {
                rankings = lexical(searchQueries, trace);
            }

            List<Candidate> fused;
            try (ExecutionTrace.Span span = trace.start("retrieval.fusion")) {
                fused = ReciprocalRankFusion.fuse(rankings, settings.getRrfK());
                span.attr("lists", rankings.size()).attr("candidates", fused.size()).attr("k", settings.getRrfK());
            }
            List<Candidate> top = fused.subList(0, Math.min(fused.size(), settings.getRerankCandidates()));

            List<Candidate> relevant = null;
            boolean reranked = false;
            try (ExecutionTrace.Span span = trace.start("retrieval.rerank")) {
                if (top.isEmpty()) {
                    span.skip("sem candidatos");
                } else if (!reranker.available()) {
                    span.skip("reranker não configurado");
                } else {
                    try {
                        List<Candidate> scored = reranker.rerank(question, top);
                        relevant = scored.stream().filter(c -> c.rerankScore() >= settings.getMinRerankScore()).toList();
                        reranked = true;
                        span.attr("model", reranker.modelName()).attr("in", top.size()).attr("kept", relevant.size())
                                .attr("topScore", round(scored.get(0).rerankScore()));
                    } catch (RuntimeException e) {
                        span.fail(e);
                        log.warn("Reranker indisponível, seguindo com a ordem da fusão: {}", e.getMessage());
                    }
                }
            }
            if (relevant == null) {
                relevant = relevanceGate(top, searchQueries, trace);
            }
            List<Candidate> selected = relevant.subList(0, Math.min(relevant.size(), settings.getFinalTopK()));

            List<RetrievedPassage> passages;
            try (ExecutionTrace.Span span = trace.start("retrieval.parent_expansion")) {
                passages = selected.isEmpty() ? List.of() : assembler.assemble(selected, firstIndex);
                span.attr("children", selected.size()).attr("passages", passages.size())
                        .attr("chars", passages.stream().mapToInt(p -> p.content().length()).sum());
            }
            return new Result(passages, fused.size(), mode, reranked);
        } catch (KnowledgeStoreException e) {
            throw new KnowledgeBaseUnavailableException("A base de conhecimento está indisponível no momento", e);
        }
    }

    /** One hybrid search per query; null when the embeddings fail (the caller falls back to full-text). */
    private List<List<ScoredChunk>> hybrid(List<String> queries, ExecutionTrace trace) {
        try (ExecutionTrace.Span span = trace.start("retrieval.hybrid")) {
            List<float[]> vectors;
            try {
                vectors = embedder.embed(queries); // one batched call for every query
            } catch (RuntimeException e) {
                span.fail(e);
                log.warn("Embeddings indisponíveis, seguindo só com busca textual: {}", e.getMessage());
                return null;
            }
            List<CompletableFuture<List<ScoredChunk>>> searches = new ArrayList<>();
            for (int i = 0; i < queries.size(); i++) {
                String query = queries.get(i);
                float[] vector = vectors.get(i);
                searches.add(CompletableFuture.supplyAsync(MdcPropagation.wrap(
                        () -> store.hybridSearch(query, vector, settings.getTopK())), executor));
            }
            List<List<ScoredChunk>> rankings = awaitAll(searches);
            span.attr("model", embedder.modelName()).attr("queries", queries.size())
                    .attr("hits", rankings.stream().mapToInt(List::size).sum())
                    .attr("topSimilarity", rankings.stream().flatMap(List::stream).map(ScoredChunk::similarity)
                            .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max().stream()
                            .mapToObj(HybridRetriever::round).findFirst().orElse(null));
            return rankings;
        }
    }

    /** Full-text only, over the parent sections: the search while embeddings are off. */
    private List<List<ScoredChunk>> lexical(List<String> queries, ExecutionTrace trace) {
        try (ExecutionTrace.Span span = trace.start("retrieval.lexical")) {
            List<CompletableFuture<List<ScoredChunk>>> searches = queries.stream()
                    .map(query -> CompletableFuture.supplyAsync(MdcPropagation.wrap(
                            () -> store.lexicalSearch(query, settings.getTopK())), executor))
                    .toList();
            List<List<ScoredChunk>> rankings = awaitAll(searches);
            span.attr("queries", queries.size()).attr("hits", rankings.stream().mapToInt(List::size).sum())
                    .attr("reason", embedder.available() ? "busca híbrida indisponível" : "embeddings não configurados");
            return rankings;
        }
    }

    /**
     * Without a reranker the fused order says which chunks are best, not whether they are relevant at all: the
     * vector side always returns its nearest neighbours. A chunk is kept when it shares enough words with the
     * question (lexical evidence) or is semantically close enough (cosine similarity).
     */
    private List<Candidate> relevanceGate(List<Candidate> candidates, List<String> queries, ExecutionTrace trace) {
        Set<String> questionWords = new HashSet<>();
        queries.forEach(query -> questionWords.addAll(PortugueseText.contentStems(query)));
        int minOverlap = Math.min(settings.getMinLexicalOverlap(), Math.max(1, questionWords.size()));
        List<Candidate> kept = candidates.stream()
                .filter(candidate -> {
                    Set<String> words = new HashSet<>(PortugueseText.contentStems(candidate.chunk().contextualText()));
                    long overlap = questionWords.stream().filter(words::contains).count();
                    boolean close = candidate.similarity() != null
                            && candidate.similarity() >= settings.getMinSemanticSimilarity();
                    return overlap >= minOverlap || close;
                })
                .toList();
        try (ExecutionTrace.Span span = trace.start("retrieval.relevance_gate")) {
            span.attr("in", candidates.size()).attr("kept", kept.size()).attr("minOverlap", minOverlap)
                    .attr("minSimilarity", settings.getMinSemanticSimilarity());
        }
        return kept;
    }

    private <T> List<T> awaitAll(List<CompletableFuture<T>> futures) {
        List<T> results = new ArrayList<>(futures.size());
        for (CompletableFuture<T> future : futures) {
            results.add(await(future));
        }
        return results;
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(settings.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new KnowledgeBaseUnavailableException("A busca na documentação excedeu o tempo limite", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Busca interrompida", e);
        }
    }

    private List<String> searchQueries(String question, List<String> queries) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        List<String> all = new ArrayList<>();
        all.add(question);
        if (queries != null) {
            all.addAll(queries);
        }
        for (String query : all) {
            if (query == null) {
                continue;
            }
            String clean = query.strip().replaceAll("\\s+", " ");
            if (clean.length() > 300) {
                clean = clean.substring(0, 300);
            }
            if (!clean.isEmpty() && seen.add(clean.toLowerCase(Locale.ROOT))) {
                result.add(clean);
            }
            if (result.size() == settings.getMaxQueries()) {
                break;
            }
        }
        return result;
    }

    private static Double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    // ------------------------------------------------------------------ LangChain4j ContentRetriever

    @Override
    public List<Content> retrieve(Query query) {
        Result result = retrieve(query.text(), List.of(query.text()), new ExecutionTrace(ObservationRegistry.NOOP));
        Function<RetrievedPassage, Content> toContent = passage -> Content.from(
                TextSegment.from(passage.content(), Metadata.from(Map.of(
                        "document", passage.section().docTitle(),
                        "section", passage.section().sectionPath(),
                        "source", passage.section().source(),
                        "parentId", passage.section().parentId()))),
                Map.of(ContentMetadata.SCORE, passage.score()));
        return result.passages().stream().map(toContent).toList();
    }
}
