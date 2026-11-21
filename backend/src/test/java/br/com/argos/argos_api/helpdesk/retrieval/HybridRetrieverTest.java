package br.com.argos.argos_api.helpdesk.retrieval;

import br.com.argos.argos_api.helpdesk.HelpdeskProperties;
import br.com.argos.argos_api.helpdesk.ingestion.MarkdownChunker;
import br.com.argos.argos_api.helpdesk.model.KnowledgeChunk;
import br.com.argos.argos_api.helpdesk.model.RetrievedPassage;
import br.com.argos.argos_api.helpdesk.model.ScoredChunk;
import br.com.argos.argos_api.helpdesk.model.TraceSpan;
import br.com.argos.argos_api.helpdesk.observability.ExecutionTrace;
import br.com.argos.argos_api.helpdesk.retrieval.HybridRetriever.Mode;
import br.com.argos.argos_api.helpdesk.retrieval.ReciprocalRankFusion.Candidate;
import br.com.argos.argos_api.helpdesk.support.ConceptEmbeddingModel;
import br.com.argos.argos_api.helpdesk.support.InMemoryKnowledgeStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import io.micrometer.observation.ObservationRegistry;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HybridRetrieverTest {

    private static final Map<String, String> DOCS = new LinkedHashMap<>();

    static {
        DOCS.put("integracoes.md", """
                # Integrações

                ## Canais de notificação

                O Argos envia notificações por Slack, Discord ou E-mail quando um incidente abre.
                Cada canal tem uma severidade mínima.

                ### Testar um canal

                Clique em Testar para validar a entrega na hora.
                """);
        DOCS.put("deploys.md", """
                # Deploys

                ## Quality Gate

                Antes de publicar, o pipeline consulta o Quality Gate. Com um incidente SEV1 ativo o deploy é bloqueado
                e a resposta é HTTP 409.
                """);
        DOCS.put("kpis.md", """
                # KPIs de negócio

                ## Impacto financeiro

                O prejuízo de um incidente é calculado pela linha de base, pelo peso do serviço e pela degradação.
                """);
    }

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HelpdeskProperties.Retrieval settings = new HelpdeskProperties.Retrieval();
    private final MarkdownChunker chunker = new MarkdownChunker(2, 3000, 600, 900);
    private final TextEmbedder concepts = embedder(new ConceptEmbeddingModel(256));

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void hybridSearchResultsAreExpandedToTheirParentSections() {
        InMemoryKnowledgeStore store = InMemoryKnowledgeStore.indexed(chunker, concepts, DOCS);
        ExecutionTrace trace = new ExecutionTrace(ObservationRegistry.NOOP);

        HybridRetriever.Result result = retriever(store, concepts, noReranker())
                .retrieve("Como recebo avisos quando um incidente abre?", List.of(), trace);

        assertThat(result.mode()).isEqualTo(Mode.HYBRID);
        RetrievedPassage best = result.passages().get(0);
        assertThat(best.index()).isEqualTo(1);
        assertThat(best.section().docTitle()).isEqualTo("Integrações");
        assertThat(best.content()).as("the parent section, with its subsection")
                .contains("## Canais de notificação", "### Testar um canal");
        assertThat(spans(trace)).containsExactly("retrieval.hybrid", "retrieval.fusion", "retrieval.rerank",
                "retrieval.relevance_gate", "retrieval.parent_expansion");
    }

    @Test
    void theSemanticSideFindsTheAnswerEvenWithoutSharedWords() {
        InMemoryKnowledgeStore store = InMemoryKnowledgeStore.indexed(chunker, concepts, DOCS);

        // "perdemos" / "dinheiro" never appear in the documents; the embedding maps them to the impact concept.
        HybridRetriever.Result result = retriever(store, concepts, noReranker())
                .retrieve("Quanto dinheiro perdemos?", List.of(), new ExecutionTrace(ObservationRegistry.NOOP));

        assertThat(result.passages()).extracting(p -> p.section().docTitle()).containsExactly("KPIs de negócio");
    }

    @Test
    void nearestNeighboursThatAreNotRelevantAreDropped() {
        InMemoryKnowledgeStore store = InMemoryKnowledgeStore.indexed(chunker, concepts, DOCS);

        // The vector side always returns its nearest chunks; the relevance gate removes them.
        HybridRetriever.Result result = retriever(store, concepts, noReranker())
                .retrieve("Qual a receita de bolo de cenoura?", List.of(), new ExecutionTrace(ObservationRegistry.NOOP));

        assertThat(result.candidates()).isPositive();
        assertThat(result.passages()).isEmpty();
    }

    @Test
    void theRerankerReordersAndDropsIrrelevantCandidates() {
        InMemoryKnowledgeStore store = InMemoryKnowledgeStore.indexed(chunker, concepts, DOCS);
        // Cross-encoder stand-in: only the Quality Gate text is relevant to this question.
        ScoringModel scoring = (segments, query) -> Response.from(segments.stream()
                .map(segment -> segment.text().contains("Quality Gate") ? 0.92 : 0.01).toList());

        HybridRetriever.Result result = retriever(store, concepts, new Reranker(Optional.of(scoring), "rerank-teste"))
                .retrieve("Por que meu deploy foi bloqueado com incidente aberto?", List.of(),
                        new ExecutionTrace(ObservationRegistry.NOOP));

        assertThat(result.reranked()).isTrue();
        assertThat(result.passages()).hasSize(1);
        assertThat(result.passages().get(0).section().sectionPath()).isEqualTo("Quality Gate");
        assertThat(result.passages().get(0).score()).isEqualTo(0.92);
    }

    @Test
    void aRerankerOutageFallsBackToTheFusedOrder() {
        InMemoryKnowledgeStore store = InMemoryKnowledgeStore.indexed(chunker, concepts, DOCS);
        ScoringModel broken = (segments, query) -> {
            throw new IllegalStateException("cohere fora do ar");
        };
        ExecutionTrace trace = new ExecutionTrace(ObservationRegistry.NOOP);

        HybridRetriever.Result result = retriever(store, concepts, new Reranker(Optional.of(broken), "rerank"))
                .retrieve("quality gate deploy bloqueado SEV1", List.of(), trace);

        assertThat(result.reranked()).isFalse();
        assertThat(result.passages()).extracting(p -> p.section().sectionPath()).first().isEqualTo("Quality Gate");
        assertThat(trace.spans()).filteredOn(span -> span.name().equals("retrieval.rerank"))
                .extracting(TraceSpan::status).containsExactly("error");
    }

    @Test
    void anEmbeddingOutageFallsBackToFullTextSearch() {
        InMemoryKnowledgeStore store = InMemoryKnowledgeStore.indexed(chunker, concepts, DOCS);
        EmbeddingModel broken = new EmbeddingModel() {
            @Override
            public Response<List<dev.langchain4j.data.embedding.Embedding>> embedAll(List<TextSegment> segments) {
                throw new IllegalStateException("openai fora do ar");
            }
        };
        ExecutionTrace trace = new ExecutionTrace(ObservationRegistry.NOOP);

        HybridRetriever.Result result = retriever(store, embedder(broken), noReranker())
                .retrieve("canal de notificação Slack", List.of(), trace);

        assertThat(result.mode()).isEqualTo(Mode.LEXICAL);
        assertThat(result.passages()).extracting(p -> p.section().docTitle()).first().isEqualTo("Integrações");
        assertThat(trace.spans()).filteredOn(span -> span.name().equals("retrieval.hybrid"))
                .extracting(TraceSpan::status).containsExactly("error");
        assertThat(spans(trace)).contains("retrieval.lexical");
    }

    @Test
    void withoutEmbeddingsTheSearchIsFullTextOverTheSections() {
        InMemoryKnowledgeStore store = InMemoryKnowledgeStore.indexed(chunker, null, DOCS);

        HybridRetriever.Result found = retriever(store, embedder(null), noReranker())
                .retrieve("quality gate deploy bloqueado", List.of(), new ExecutionTrace(ObservationRegistry.NOOP));
        HybridRetriever.Result offTopic = retriever(store, embedder(null), noReranker())
                .retrieve("Qual a receita de bolo de cenoura?", List.of(), new ExecutionTrace(ObservationRegistry.NOOP));

        assertThat(found.mode()).isEqualTo(Mode.LEXICAL);
        assertThat(found.passages()).extracting(p -> p.section().sectionPath()).first().isEqualTo("Quality Gate");
        assertThat(offTopic.passages()).isEmpty();
        assertThat(store.hybridSearches).hasValue(0);
    }

    @Test
    void anUnreachableStoreIsReportedAsUnavailable() {
        InMemoryKnowledgeStore store = InMemoryKnowledgeStore.indexed(chunker, concepts, DOCS);
        store.down = true;

        assertThatThrownBy(() -> retriever(store, concepts, noReranker())
                .retrieve("quality gate", List.of(), new ExecutionTrace(ObservationRegistry.NOOP)))
                .isInstanceOf(KnowledgeBaseUnavailableException.class);
    }

    @Test
    void reformulationsAreSearchedTooAndDeduplicated() {
        InMemoryKnowledgeStore store = InMemoryKnowledgeStore.indexed(chunker, concepts, DOCS);

        retriever(store, concepts, noReranker()).retrieve("Quality Gate",
                List.of("quality gate", "deploy bloqueado SEV1", "prejuízo", "quarta consulta"),
                new ExecutionTrace(ObservationRegistry.NOOP));

        assertThat(store.hybridSearches).as("question + 2 reformulations, duplicates removed").hasValue(3);
    }

    @Test
    void itIsAlsoALangChain4jContentRetriever() {
        InMemoryKnowledgeStore store = InMemoryKnowledgeStore.indexed(chunker, concepts, DOCS);

        List<Content> contents = retriever(store, concepts, noReranker()).retrieve(Query.from("quality gate deploy"));

        assertThat(contents).isNotEmpty();
        assertThat(contents.get(0).textSegment().metadata().getString("document")).isEqualTo("Deploys");
    }

    @Test
    void fusionAcrossQueriesRewardsChunksFoundByMoreThanOneQuery() {
        KnowledgeChunk a = chunk("a");
        KnowledgeChunk b = chunk("b");
        KnowledgeChunk c = chunk("c");
        List<Candidate> fused = ReciprocalRankFusion.fuse(List.of(
                List.of(new ScoredChunk(a, 0.03, 0.62), new ScoredChunk(b, 0.02, 0.55)),
                List.of(new ScoredChunk(c, 0.03, 0.70), new ScoredChunk(b, 0.02, 0.58))), 60);

        assertThat(fused).extracting(candidate -> candidate.chunk().chunkId()).containsExactly("b", "a", "c");
        Candidate first = fused.get(0);
        assertThat(first.fusedScore()).isEqualTo(2.0 / 62, Offset.offset(1e-9));
        assertThat(first.similarity()).isEqualTo(0.58);
        assertThat(first.bestRank()).isEqualTo(2);
    }

    private HybridRetriever retriever(InMemoryKnowledgeStore store, TextEmbedder embedder, Reranker reranker) {
        return new HybridRetriever(store, embedder, reranker, new ContextAssembler(store, settings), settings, executor);
    }

    private static TextEmbedder embedder(EmbeddingModel model) {
        return new TextEmbedder(Optional.ofNullable(model), "conceitos", 256, 16);
    }

    private static Reranker noReranker() {
        return new Reranker(Optional.empty(), null);
    }

    private static List<String> spans(ExecutionTrace trace) {
        return trace.spans().stream().map(TraceSpan::name).toList();
    }

    private static KnowledgeChunk chunk(String id) {
        return new KnowledgeChunk(id, "p-" + id, "doc", "Doc", "", "doc.md", id, id, 1);
    }
}
