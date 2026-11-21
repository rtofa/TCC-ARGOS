package br.com.argos.argos_api.helpdesk.agent;

import br.com.argos.argos_api.ai.llm.LlmCallLog;
import br.com.argos.argos_api.ai.llm.LlmRouter;
import br.com.argos.argos_api.helpdesk.model.ChatTurn;
import br.com.argos.argos_api.helpdesk.model.QueryAnalysis;
import br.com.argos.argos_api.helpdesk.model.QueryAnalysis.Intent;
import br.com.argos.argos_api.helpdesk.observability.MdcPropagation;
import br.com.argos.argos_api.helpdesk.prompt.PromptLibrary;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * "Entender pergunta": classifies the question (platform question, greeting, out of scope), rewrites follow-ups into
 * a standalone question and proposes reformulations for retrieval (query expansion).
 *
 * <p>It is a LangChain4j AI service with structured output, running on the LLM router. When no model is available,
 * it fails or it takes longer than {@code timeout}, a heuristic takes over: the agent never stops because of this step.
 */
public class QueryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(QueryAnalyzer.class);

    /** Structured output requested from the model. */
    public record Analysis(
            @Description("PLATFORM_QUESTION, GREETING ou OUT_OF_SCOPE") Intent intent,
            @Description("a pergunta reescrita para ser entendida sem o histórico") String standaloneQuestion,
            @Description("de 1 a 2 reformulações curtas para busca na documentação") List<String> searchQueries) {
    }

    public interface Analyst {
        Analysis analyze(@UserMessage String input);
    }

    private static final Pattern GREETING = Pattern.compile(
            "^(oi+|ol[aá]|opa|e a[ií]|bom dia|boa tarde|boa noite|hello|hi|hey|obrigad[oa]|muito obrigad[oa]|valeu|tchau|"
                    + "at[eé] mais|blz|beleza|ok|certo)[\\s!.,?]*(lia|argos)?[\\s!.,?]*(tudo bem|tudo bom)?[\\s!.,?]*$");
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "^(e |e o |e a |e os |e as |e se |mas |tamb[eé]m|como assim|isso|esse|essa|ele|ela|onde fica|por qu[eê]|e para|e no|e na)");

    private final Analyst analyst;
    private final PromptLibrary prompts;
    private final ExecutorService executor;
    private final Duration timeout;

    public QueryAnalyzer(LlmRouter router, PromptLibrary prompts, ExecutorService executor, Duration timeout) {
        this.prompts = prompts;
        this.executor = executor;
        this.timeout = timeout;
        this.analyst = router.available()
                ? AiServices.builder(Analyst.class)
                .chatModel(router)
                .systemMessageProvider(memoryId -> prompts.queryAnalysis())
                .build()
                : null;
    }

    public QueryAnalysis analyze(String question, List<ChatTurn> history) {
        if (analyst == null) {
            return heuristic(question, history);
        }
        String input = prompts.analysisInput(question, history);
        // The call runs on its own virtual thread so it can be abandoned at the timeout; its model calls are
        // recorded there and merged into this execution's log.
        CompletableFuture<Result> call = CompletableFuture.supplyAsync(MdcPropagation.wrap(() -> {
            LlmCallLog.start();
            try {
                return new Result(analyst.analyze(input), null, LlmCallLog.stop());
            } catch (RuntimeException e) {
                return new Result(null, e, LlmCallLog.stop());
            }
        }), executor);
        try {
            Result result = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            LlmCallLog.merge(result.calls());
            if (result.error() != null) {
                log.warn("Análise da pergunta falhou, usando heurística: {}", result.error().getMessage());
                return heuristic(question, history);
            }
            return sanitize(result.analysis(), question, history);
        } catch (TimeoutException e) {
            call.cancel(true);
            log.warn("Análise da pergunta excedeu {} s, usando heurística", timeout.toSeconds());
            return heuristic(question, history);
        } catch (ExecutionException e) {
            return heuristic(question, history);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return heuristic(question, history);
        }
    }

    private record Result(Analysis analysis, RuntimeException error, List<LlmCallLog.LlmCall> calls) {
    }

    private QueryAnalysis sanitize(Analysis analysis, String question, List<ChatTurn> history) {
        if (analysis == null || analysis.intent() == null) {
            return heuristic(question, history);
        }
        String standalone = analysis.standaloneQuestion() == null || analysis.standaloneQuestion().isBlank()
                ? question : clip(analysis.standaloneQuestion(), 500);
        Set<String> queries = new LinkedHashSet<>();
        queries.add(standalone);
        if (analysis.searchQueries() != null) {
            analysis.searchQueries().stream().filter(q -> q != null && !q.isBlank()).map(q -> clip(q, 200))
                    .limit(2).forEach(queries::add);
        }
        // A greeting with a real question attached is a question.
        Intent intent = analysis.intent() == Intent.GREETING && !GREETING.matcher(fold(question)).matches()
                ? Intent.PLATFORM_QUESTION : analysis.intent();
        return new QueryAnalysis(intent, standalone, List.copyOf(queries), true);
    }

    /** No model: greetings by pattern, follow-ups glued to the previous question, everything else searched as is. */
    static QueryAnalysis heuristic(String question, List<ChatTurn> history) {
        String folded = fold(question);
        if (GREETING.matcher(folded).matches()) {
            return new QueryAnalysis(Intent.GREETING, question, List.of(question), false);
        }
        String standalone = question;
        String previous = lastUserQuestion(history);
        if (previous != null && (FOLLOW_UP.matcher(folded).find() || folded.split("\\s+").length <= 4)) {
            standalone = previous + " " + question;
        }
        return new QueryAnalysis(Intent.PLATFORM_QUESTION, standalone, List.of(standalone), false);
    }

    private static String lastUserQuestion(List<ChatTurn> history) {
        List<ChatTurn> reversed = new ArrayList<>(history);
        java.util.Collections.reverse(reversed);
        return reversed.stream().filter(turn -> turn.role() == ChatTurn.Role.USER).map(ChatTurn::content)
                .findFirst().orElse(null);
    }

    private static String fold(String text) {
        return text.strip().toLowerCase(Locale.ROOT);
    }

    private static String clip(String text, int max) {
        String clean = text.strip().replaceAll("\\s+", " ");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }
}
