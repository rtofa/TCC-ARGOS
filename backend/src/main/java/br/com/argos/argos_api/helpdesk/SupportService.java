package br.com.argos.argos_api.helpdesk;

import br.com.argos.argos_api.ai.llm.LlmRouter;
import br.com.argos.argos_api.helpdesk.agent.AgentListener;
import br.com.argos.argos_api.helpdesk.agent.SupportAgent;
import br.com.argos.argos_api.helpdesk.ingestion.KnowledgeBaseIngestor;
import br.com.argos.argos_api.helpdesk.model.IngestionReport;
import br.com.argos.argos_api.helpdesk.model.KnowledgeStatus;
import br.com.argos.argos_api.helpdesk.model.SourceReference;
import br.com.argos.argos_api.helpdesk.model.SupportAnswer;
import br.com.argos.argos_api.helpdesk.model.SupportRequest;
import br.com.argos.argos_api.helpdesk.observability.MdcPropagation;
import br.com.argos.argos_api.helpdesk.retrieval.KnowledgeBaseUnavailableException;
import br.com.argos.argos_api.helpdesk.retrieval.Reranker;
import br.com.argos.argos_api.helpdesk.retrieval.TextEmbedder;
import br.com.argos.argos_api.helpdesk.store.KnowledgeStore;
import br.com.argos.argos_api.helpdesk.store.StoredDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous, non-blocking entry point of the support agent.
 *
 * <ul>
 *   <li>Each question runs on its own virtual thread; the servlet thread is released at once
 *       ({@link CompletableFuture} for JSON, {@link SseEmitter} for streaming).</li>
 *   <li>A bulkhead caps the questions answered at the same time; beyond it the caller gets HTTP 429 instead of
 *       queueing until timeouts.</li>
 *   <li>Every execution has a deadline.</li>
 * </ul>
 */
public class SupportService {

    private static final Logger log = LoggerFactory.getLogger(SupportService.class);

    private final SupportAgent agent;
    private final KnowledgeBaseIngestor ingestor;
    private final KnowledgeStore store;
    private final TextEmbedder embedder;
    private final Reranker reranker;
    private final LlmRouter router;
    private final ExecutorService executor;
    private final HelpdeskProperties.Execution settings;
    private final Semaphore permits;

    public SupportService(SupportAgent agent, KnowledgeBaseIngestor ingestor, KnowledgeStore store, TextEmbedder embedder,
                          Reranker reranker, LlmRouter router, ExecutorService executor,
                          HelpdeskProperties.Execution settings) {
        this.agent = agent;
        this.ingestor = ingestor;
        this.store = store;
        this.embedder = embedder;
        this.reranker = reranker;
        this.router = router;
        this.executor = executor;
        this.settings = settings;
        this.permits = new Semaphore(settings.getMaxConcurrent(), true);
    }

    /** Full answer as JSON once it is ready. */
    public CompletableFuture<SupportAnswer> ask(SupportRequest request) {
        acquire();
        return CompletableFuture.supplyAsync(MdcPropagation.wrap(() -> {
                    try {
                        return agent.answer(request, AgentListener.NONE);
                    } finally {
                        permits.release();
                    }
                }), executor)
                .orTimeout(settings.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Streams the execution as server-sent events: {@code stage}, {@code sources}, {@code token} (pieces of the answer
     * as the model writes them), then {@code done} with the validated answer, or {@code error}.
     *
     * <p>The final answer in {@code done} is the one to show: the output guardrail may replace a draft that was
     * streamed but turned out not to be supported by the documents.
     */
    public SseEmitter stream(SupportRequest request) {
        acquire();
        SseEmitter emitter = new SseEmitter(settings.getTimeout().toMillis());
        SseListener listener = new SseListener(emitter);
        emitter.onTimeout(listener::cancel);
        emitter.onError(error -> listener.cancel());
        emitter.onCompletion(listener::cancel);
        try {
            executor.submit(MdcPropagation.wrap(() -> {
                try {
                    SupportAnswer answer = agent.answer(request, listener);
                    listener.send("done", answer);
                    emitter.complete();
                } catch (CancellationException e) {
                    log.info("Cliente encerrou o streaming antes do fim");
                } catch (KnowledgeBaseUnavailableException e) {
                    listener.send("error", Map.of("status", 503, "message", e.getMessage()));
                    emitter.complete();
                } catch (RuntimeException e) {
                    log.error("Falha inesperada no agente de suporte", e);
                    listener.send("error", Map.of("status", 500, "message", "Não foi possível responder agora. Tente novamente."));
                    emitter.complete();
                } finally {
                    permits.release();
                }
            }));
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
        return emitter;
    }

    public CompletableFuture<IngestionReport> reindex(boolean force) {
        return CompletableFuture.supplyAsync(MdcPropagation.wrap(() -> ingestor.ingest(force)), executor);
    }

    public KnowledgeStatus status() {
        boolean available = store.available();
        List<KnowledgeStatus.IndexedDocument> documents = List.of();
        long chunks = 0;
        if (available) {
            try {
                documents = store.documents().values().stream().map(SupportService::toIndexed).toList();
                chunks = store.chunkCount();
            } catch (RuntimeException e) {
                available = false;
            }
        }
        List<String> models = router.status().stream()
                .map(route -> route.name() + ": " + route.model() + " (" + route.state().name().toLowerCase() + ")")
                .toList();
        KnowledgeStatus.Capabilities capabilities = new KnowledgeStatus.Capabilities(available,
                available && embedder.available() && store.hybridReady(),
                embedder.modelName(), reranker.available(), reranker.modelName(), models);
        return new KnowledgeStatus(available, ingestor.running(), documents, chunks, capabilities, ingestor.lastReport(),
                ingestor.lastError());
    }

    private static KnowledgeStatus.IndexedDocument toIndexed(StoredDocument document) {
        Instant indexedAt = document.indexedAt() == null ? null : Instant.parse(document.indexedAt());
        return new KnowledgeStatus.IndexedDocument(document.docId(), document.title(), document.source(),
                document.parents(), document.chunks(), document.embedded(), indexedAt);
    }

    private void acquire() {
        try {
            if (!permits.tryAcquire(settings.getAcquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new HelpdeskBusyException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HelpdeskBusyException();
        }
    }

    /** Pushes agent progress to the client; once the client is gone, stops the agent at its next stage. */
    private static final class SseListener implements AgentListener {

        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean();

        SseListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onStage(String stage) {
            send("stage", Map.of("name", stage));
        }

        @Override
        public void onSources(List<SourceReference> sources) {
            send("sources", sources);
        }

        @Override
        public void onToken(String token) {
            send("token", Map.of("text", token));
        }

        @Override
        public boolean cancelled() {
            return closed.get();
        }

        void cancel() {
            closed.set(true);
        }

        void send(String event, Object data) {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                closed.set(true);
            }
        }
    }
}
