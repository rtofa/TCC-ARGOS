package br.com.argos.argos_api.helpdesk.agent;

import br.com.argos.argos_api.ai.llm.AllRoutesFailedException;
import br.com.argos.argos_api.ai.llm.LlmCallLog;
import br.com.argos.argos_api.ai.llm.LlmRouter;
import br.com.argos.argos_api.helpdesk.guardrail.InputGuard;
import br.com.argos.argos_api.helpdesk.guardrail.OutputGuard;
import br.com.argos.argos_api.helpdesk.model.AnswerTrace;
import br.com.argos.argos_api.helpdesk.model.Outcome;
import br.com.argos.argos_api.helpdesk.model.QueryAnalysis;
import br.com.argos.argos_api.helpdesk.model.RetrievedPassage;
import br.com.argos.argos_api.helpdesk.model.SourceReference;
import br.com.argos.argos_api.helpdesk.model.SupportAnswer;
import br.com.argos.argos_api.helpdesk.model.SupportRequest;
import br.com.argos.argos_api.helpdesk.observability.ExecutionTrace;
import br.com.argos.argos_api.helpdesk.observability.MdcPropagation;
import br.com.argos.argos_api.helpdesk.prompt.PromptLibrary;
import br.com.argos.argos_api.helpdesk.retrieval.HybridRetriever;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Orchestration of the support agent. One question goes through:
 *
 * <pre>
 * guardrail de entrada ─► entender pergunta ─► busca híbrida (textual + vetorial, RRF ─► rerank ─► seções pai)
 *        │ bloqueia             │ fora do escopo / saudação        │ nada relevante
 *        ▼                      ▼                                   ▼
 *    BLOCKED          OUT_OF_SCOPE / GREETING                  NOT_FOUND
 *
 * ─► gerar resposta (streaming, fallback de modelos, ferramenta de busca) ─► guardrail de saída ─► ANSWERED com fontes
 *        │ nenhum modelo respondeu                                              │ sem base nos documentos
 *        ▼                                                                      ▼
 *    DEGRADED (trechos da documentação)                                    NOT_FOUND
 * </pre>
 *
 * Synchronous on purpose: it runs on a virtual thread (see {@code SupportService}), so blocking on the model or the
 * search cluster costs no platform thread. Every stage is traced.
 */
public class SupportAgent {

    private static final Logger log = LoggerFactory.getLogger(SupportAgent.class);

    private final InputGuard inputGuard;
    private final QueryAnalyzer analyzer;
    private final HybridRetriever retriever;
    private final AnswerGenerator generator;
    private final OutputGuard outputGuard;
    private final PromptLibrary prompts;
    private final LlmRouter router;
    private final ObservationRegistry observations;

    public SupportAgent(InputGuard inputGuard, QueryAnalyzer analyzer, HybridRetriever retriever,
                        AnswerGenerator generator, OutputGuard outputGuard, PromptLibrary prompts, LlmRouter router,
                        ObservationRegistry observations) {
        this.inputGuard = inputGuard;
        this.analyzer = analyzer;
        this.retriever = retriever;
        this.generator = generator;
        this.outputGuard = outputGuard;
        this.prompts = prompts;
        this.router = router;
        this.observations = observations;
    }

    /**
     * @throws br.com.argos.argos_api.helpdesk.retrieval.KnowledgeBaseUnavailableException when the documentation
     *         cannot be searched
     */
    public SupportAnswer answer(SupportRequest request, AgentListener listener) {
        ExecutionTrace trace = new ExecutionTrace(observations);
        MDC.put(MdcPropagation.TRACE_ID, trace.traceId());
        LlmCallLog.start();
        Run run = new Run(trace, listener);
        try {
            return run.execute(request);
        } finally {
            MDC.remove(MdcPropagation.TRACE_ID);
            LlmCallLog.stop();
        }
    }

    /** State of one execution. */
    private final class Run {

        private final ExecutionTrace trace;
        private final AgentListener listener;

        Run(ExecutionTrace trace, AgentListener listener) {
            this.trace = trace;
            this.listener = listener;
        }

        SupportAnswer execute(SupportRequest request) {
            // 1. Input guardrail
            stage("guardrail");
            InputGuard.Verdict input;
            try (ExecutionTrace.Span span = trace.start("guardrail.input")) {
                input = inputGuard.check(request);
                span.attr("allowed", input.allowed()).attr("reason", input.reason())
                        .attr("maskedPii", input.maskedItems()).attr("droppedHistory", input.droppedTurns());
            }
            if (!input.allowed()) {
                return finish(Outcome.BLOCKED, prompts.blocked(), List.of(), input.reason());
            }

            // 2. Understand the question
            stage("analysis");
            QueryAnalysis analysis;
            try (ExecutionTrace.Span span = trace.start("query_analysis")) {
                analysis = analyzer.analyze(input.question(), input.history());
                span.attr("intent", analysis.intent().name()).attr("byModel", analysis.byModel())
                        .attr("standalone", analysis.standaloneQuestion()).attr("queries", analysis.searchQueries());
            }
            switch (analysis.intent()) {
                case GREETING -> {
                    return finish(Outcome.GREETING, prompts.greeting(), List.of(), null);
                }
                case OUT_OF_SCOPE -> {
                    return finish(Outcome.OUT_OF_SCOPE, prompts.outOfScope(), List.of(), "fora do escopo");
                }
                case PLATFORM_QUESTION -> {
                    // continues below
                }
            }

            // 3. Hybrid retrieval
            stage("retrieval");
            HybridRetriever.Result retrieval = retriever.retrieve(analysis.standaloneQuestion(), analysis.searchQueries(),
                    trace);
            List<RetrievedPassage> passages = retrieval.passages();
            if (passages.isEmpty()) {
                return finish(Outcome.NOT_FOUND, OutputGuard.NOT_FOUND_MESSAGE, List.of(), "nenhum trecho relevante");
            }
            listener.onSources(references(passages));

            // 4. Generation (streaming, fallback between models, documentation tool)
            if (!generator.available()) {
                return finish(Outcome.DEGRADED, prompts.degraded(), references(passages), "nenhum modelo configurado");
            }
            stage("generation");
            AnswerGenerator.Generation generation;
            try {
                generation = generator.generate(analysis.standaloneQuestion(), passages, listener, trace);
            } catch (AllRoutesFailedException | LlmRouter.PartialResponseException e) {
                log.warn("Nenhum modelo concluiu a resposta: {}", e.getMessage());
                return finish(Outcome.DEGRADED, prompts.degraded(), references(passages), e.getMessage());
            }

            // 5. Output guardrail
            stage("validation");
            OutputGuard.Verdict output;
            try (ExecutionTrace.Span span = trace.start("guardrail.output")) {
                output = outputGuard.check(generation.text(), generation.passages());
                span.attr("status", output.status().name()).attr("groundedness", Math.round(output.groundedness() * 100) / 100.0)
                        .attr("cited", output.citedPassages()).attr("issues", output.issues())
                        .attr("toolCalls", generation.toolCalls());
            }
            return switch (output.status()) {
                case GROUNDED -> finish(Outcome.ANSWERED, output.answer(),
                        cited(generation.passages(), output.citedPassages()), null);
                case NOT_FOUND -> finish(Outcome.NOT_FOUND, output.answer(), List.of(), "o modelo não encontrou nos trechos");
                case REJECTED -> finish(Outcome.NOT_FOUND, OutputGuard.NOT_FOUND_MESSAGE, List.of(),
                        "resposta rejeitada pelo guardrail: " + String.join("; ", output.issues()));
            };
        }

        private void stage(String name) {
            if (listener.cancelled()) {
                throw new CancellationException("O cliente encerrou a conexão");
            }
            listener.onStage(name);
        }

        private SupportAnswer finish(Outcome outcome, String text, List<SourceReference> sources, String reason) {
            List<LlmCallLog.LlmCall> calls = LlmCallLog.snapshot();
            List<AnswerTrace.ModelCall> modelCalls = calls.stream()
                    .map(call -> new AnswerTrace.ModelCall(call.route(), call.model(), call.outcome().name(),
                            call.durationMs(), call.inputTokens(), call.outputTokens(), call.error()))
                    .toList();
            boolean fallbackUsed = calls.stream().anyMatch(call -> call.outcome() != LlmCallLog.Outcome.OK
                    && call.outcome() != LlmCallLog.Outcome.RATE_LIMITED);
            int inputTokens = calls.stream().mapToInt(LlmCallLog.LlmCall::inputTokens).sum();
            int outputTokens = calls.stream().mapToInt(LlmCallLog.LlmCall::outputTokens).sum();
            AnswerTrace answerTrace = new AnswerTrace(trace.traceId(), trace.elapsedMs(), trace.spans(), modelCalls,
                    fallbackUsed, inputTokens, outputTokens);

            log.atInfo().setMessage("helpdesk.answer")
                    .addKeyValue("traceId", trace.traceId())
                    .addKeyValue("outcome", outcome.name())
                    .addKeyValue("reason", reason)
                    .addKeyValue("totalMs", answerTrace.totalMs())
                    .addKeyValue("sources", sources.size())
                    .addKeyValue("models", modelCalls.stream().map(c -> c.model() + ":" + c.outcome()).toList())
                    .addKeyValue("fallbackUsed", fallbackUsed)
                    .addKeyValue("inputTokens", inputTokens)
                    .addKeyValue("outputTokens", outputTokens)
                    .log();
            return new SupportAnswer(trace.traceId(), outcome, text, sources, answerTrace);
        }
    }

    private static List<SourceReference> references(List<RetrievedPassage> passages) {
        return passages.stream().map(RetrievedPassage::toReference).toList();
    }

    private static List<SourceReference> cited(List<RetrievedPassage> passages, Set<Integer> cited) {
        return passages.stream().filter(passage -> cited.contains(passage.index())).map(RetrievedPassage::toReference)
                .toList();
    }
}
