package br.com.argos.argos_api.helpdesk;

import br.com.argos.argos_api.ai.llm.LlmRouter;
import br.com.argos.argos_api.helpdesk.agent.AnswerGenerator;
import br.com.argos.argos_api.helpdesk.agent.QueryAnalyzer;
import br.com.argos.argos_api.helpdesk.agent.SupportAgent;
import br.com.argos.argos_api.helpdesk.guardrail.InputGuard;
import br.com.argos.argos_api.helpdesk.guardrail.OutputGuard;
import br.com.argos.argos_api.helpdesk.ingestion.DocumentLoader;
import br.com.argos.argos_api.helpdesk.ingestion.KnowledgeBaseIngestor;
import br.com.argos.argos_api.helpdesk.ingestion.MarkdownChunker;
import br.com.argos.argos_api.helpdesk.ingestion.StartupIngestion;
import br.com.argos.argos_api.helpdesk.prompt.PromptLibrary;
import br.com.argos.argos_api.helpdesk.retrieval.ContextAssembler;
import br.com.argos.argos_api.helpdesk.retrieval.HybridRetriever;
import br.com.argos.argos_api.helpdesk.retrieval.Reranker;
import br.com.argos.argos_api.helpdesk.retrieval.TextEmbedder;
import br.com.argos.argos_api.helpdesk.store.KnowledgeStore;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wiring of the support agent. Components receive only the settings they need, so they can be built directly in
 * unit tests with fakes.
 */
@Configuration
@EnableConfigurationProperties(HelpdeskProperties.class)
class HelpdeskConfiguration {

    /**
     * One virtual thread per task: blocking on the model or the search cluster costs no platform thread. Wrapped so
     * it is not mistaken for the application's default {@code Executor}.
     */
    static final class HelpdeskThreads implements AutoCloseable {
        final ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("helpdesk-", 0).factory());

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    @Bean
    HelpdeskThreads helpdeskThreads() {
        return new HelpdeskThreads();
    }

    @Bean
    PromptLibrary helpdeskPrompts() {
        return new PromptLibrary();
    }

    @Bean
    MarkdownChunker markdownChunker(HelpdeskProperties properties) {
        return new MarkdownChunker(properties.getChunking());
    }

    @Bean
    HybridRetriever hybridRetriever(KnowledgeStore store, TextEmbedder embedder, Reranker reranker,
                                    HelpdeskProperties properties, HelpdeskThreads helpdeskThreads) {
        return new HybridRetriever(store, embedder, reranker, new ContextAssembler(store, properties.getRetrieval()),
                properties.getRetrieval(), helpdeskThreads.executor);
    }

    @Bean
    SupportAgent supportAgent(HelpdeskProperties properties, LlmRouter llmRouter, HybridRetriever hybridRetriever,
                              PromptLibrary helpdeskPrompts, HelpdeskThreads helpdeskThreads,
                              ObjectProvider<ObservationRegistry> observations) {
        HelpdeskProperties.Guardrails guardrails = properties.getGuardrails();
        HelpdeskProperties.Generation generation = properties.getGeneration();
        return new SupportAgent(
                new InputGuard(guardrails.getMaxQuestionChars(), generation.getMaxHistoryTurns()),
                new QueryAnalyzer(llmRouter, helpdeskPrompts, helpdeskThreads.executor, generation.getAnalysisTimeout()),
                hybridRetriever,
                new AnswerGenerator(llmRouter, helpdeskPrompts, hybridRetriever, generation.getMaxToolCalls(),
                        properties.getRetrieval().getMaxPassages()),
                new OutputGuard(guardrails.getMinGroundedness(), guardrails.isCheckFacts()),
                helpdeskPrompts,
                llmRouter,
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    KnowledgeBaseIngestor knowledgeBaseIngestor(HelpdeskProperties properties, ResourcePatternResolver resolver,
                                                MarkdownChunker markdownChunker, TextEmbedder embedder,
                                                KnowledgeStore store, Clock clock) {
        return new KnowledgeBaseIngestor(new DocumentLoader(resolver, properties.getKnowledgeBase().getLocation()),
                markdownChunker, embedder, store, clock);
    }

    @Bean(destroyMethod = "stop")
    StartupIngestion startupIngestion(KnowledgeBaseIngestor knowledgeBaseIngestor, HelpdeskProperties properties,
                                      HelpdeskThreads helpdeskThreads) {
        return new StartupIngestion(knowledgeBaseIngestor, properties.getKnowledgeBase(), helpdeskThreads.executor);
    }

    @Bean
    SupportService supportService(SupportAgent supportAgent, KnowledgeBaseIngestor knowledgeBaseIngestor,
                                  KnowledgeStore store, TextEmbedder embedder, Reranker reranker, LlmRouter llmRouter,
                                  HelpdeskThreads helpdeskThreads, HelpdeskProperties properties) {
        return new SupportService(supportAgent, knowledgeBaseIngestor, store, embedder, reranker, llmRouter,
                helpdeskThreads.executor, properties.getExecution());
    }
}
