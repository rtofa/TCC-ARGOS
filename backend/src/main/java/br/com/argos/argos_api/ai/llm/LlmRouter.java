package br.com.argos.argos_api.ai.llm;

import br.com.argos.argos_api.ai.llm.LlmCallLog.LlmCall;
import br.com.argos.argos_api.ai.llm.LlmCallLog.Outcome;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM routing for API failures: a {@link ChatModel} that sends each request down an ordered chain of models.
 *
 * <ul>
 *   <li>Transient failures (timeout, 429, 5xx, overload, network) trip the model's circuit breaker and move on.</li>
 *   <li>Model-specific failures (model unavailable, request rejected by that model, refusal) move on without
 *       counting against the model's health.</li>
 *   <li>Authentication failures stop immediately: every model shares the same key.</li>
 * </ul>
 *
 * Because it is a plain {@code ChatModel}, LangChain4j agents, tools, memory and guardrails run on top of it
 * unchanged, and a failover can happen in the middle of an agent's tool loop.
 */
public class LlmRouter implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    public record RouteStatus(String name, String model, CircuitBreaker.State state) {
    }

    private enum FailureKind { TRANSIENT, MODEL_SPECIFIC, FATAL }

    private final String purpose;
    private final List<LlmRoute> routes;

    /** "Please try again in 17.3s" (Groq/OpenAI) or "retryDelay": "17s" (Gemini). */
    private static final Pattern RETRY_AFTER = Pattern.compile(
            "(?i)(?:try again in|retry in|retrydelay\"?\\s*[:=]\\s*\"?)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(ms|s|m)?");

    private final Duration maxRateLimitWait;

    public LlmRouter(String purpose, List<LlmRoute> routes) {
        this(purpose, routes, Duration.ZERO);
    }

    /**
     * @param maxRateLimitWait how long one request may wait for a per-minute quota to free up before moving to the
     *                         next model. Background work (investigations) can afford to wait and keep the best model;
     *                         interactive work waits less.
     */
    public LlmRouter(String purpose, List<LlmRoute> routes, Duration maxRateLimitWait) {
        this.purpose = purpose;
        this.routes = List.copyOf(routes);
        this.maxRateLimitWait = maxRateLimitWait;
    }

    public boolean available() {
        return !routes.isEmpty();
    }

    public List<RouteStatus> status() {
        return routes.stream().map(route -> new RouteStatus(route.name(), route.model(), route.breaker().state()))
                .toList();
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        List<String> failures = new ArrayList<>();
        RuntimeException lastError = null;
        for (LlmRoute route : routes) {
            if (!route.breaker().tryAcquire()) {
                failures.add(route.name() + ": circuito aberto");
                LlmCallLog.record(new LlmCall(route.name(), route.model(), Outcome.SKIPPED, "circuito aberto", 0,
                        0, 0, 0, 0, BigDecimal.ZERO));
                continue;
            }
            Duration waited = Duration.ZERO;
            while (true) {
            long started = System.nanoTime();
            try {
                ChatResponse response = route.chatModel().chat(request);
                long elapsed = elapsedMs(started);
                if (isRefusal(response)) {
                    route.breaker().onNeutral();
                    record(route, Outcome.REFUSED, "recusa do modelo", elapsed, response.tokenUsage());
                    failures.add(route.name() + ": recusa");
                    lastError = new ContentFilteredException("O modelo " + route.model() + " recusou a solicitação");
                    break;
                }
                route.breaker().onSuccess();
                record(route, Outcome.OK, null, elapsed, response.tokenUsage());
                return response;
            } catch (RuntimeException error) {
                long elapsed = elapsedMs(started);
                // A per-minute quota is not an outage: when the provider says how long to wait and we can afford
                // it, wait and retry the same (best) model instead of degrading to the next one.
                Optional<Duration> retryAfter = retryAfter(error);
                if (retryAfter.isPresent() && waited.plus(retryAfter.get()).compareTo(maxRateLimitWait) <= 0) {
                    Duration pause = retryAfter.get().plusMillis(250);
                    record(route, Outcome.RATE_LIMITED, "limite por minuto: aguardando " + pause.toMillis() + " ms",
                            elapsed, null);
                    log.info("[{}] modelo {} no limite por minuto, aguardando {} ms", purpose, route.model(), pause.toMillis());
                    sleep(pause);
                    waited = waited.plus(pause);
                    continue;
                }
                FailureKind kind = classify(error);
                record(route, Outcome.FAILED, summary(error), elapsed, null);
                log.warn("[{}] modelo {} falhou ({}): {}", purpose, route.model(), kind, summary(error));
                switch (kind) {
                    case FATAL -> {
                        route.breaker().onNeutral();
                        throw error;
                    }
                    case TRANSIENT -> route.breaker().onFailure();
                    case MODEL_SPECIFIC -> route.breaker().onNeutral();
                }
                failures.add(route.name() + ": " + summary(error));
                lastError = error;
                break;
            }
            }
        }
        throw new AllRoutesFailedException(failures, lastError);
    }

    static Optional<Duration> retryAfter(Throwable error) {
        boolean rateLimited = error instanceof RateLimitException
                || (error instanceof HttpException http && http.statusCode() == 429)
                || (error.getMessage() != null && error.getMessage().toLowerCase().contains("rate limit"));
        if (!rateLimited || error.getMessage() == null) {
            return Optional.empty();
        }
        Matcher matcher = RETRY_AFTER.matcher(error.getMessage());
        if (!matcher.find()) {
            return Optional.empty();
        }
        double amount = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2) == null ? "s" : matcher.group(2).toLowerCase();
        long millis = switch (unit) {
            case "ms" -> (long) amount;
            case "m" -> (long) (amount * 60_000);
            default -> (long) (amount * 1000);
        };
        return Optional.of(Duration.ofMillis(millis));
    }

    private static void sleep(Duration pause) {
        try {
            Thread.sleep(pause.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompido enquanto aguardava o limite do modelo", e);
        }
    }

    /** A refusal comes back as a normal response with no text and no tool call (stop reason "refusal"). */
    private static boolean isRefusal(ChatResponse response) {
        AiMessage message = response.aiMessage();
        boolean empty = message == null
                || (!message.hasToolExecutionRequests() && (message.text() == null || message.text().isBlank()));
        return empty && response.finishReason() == FinishReason.OTHER;
    }

    static FailureKind classify(Throwable error) {
        if (error instanceof AuthenticationException) {
            return FailureKind.FATAL;
        }
        if (error instanceof ModelNotFoundException) {
            return FailureKind.TRANSIENT; // the model is not available to this account right now
        }
        if (error instanceof InvalidRequestException || error instanceof ContentFilteredException) {
            return FailureKind.MODEL_SPECIFIC;
        }
        if (error instanceof RateLimitException || error instanceof TimeoutException
                || error instanceof InternalServerException || error instanceof RetriableException) {
            return FailureKind.TRANSIENT;
        }
        if (error instanceof HttpException http) {
            int status = http.statusCode();
            if (status == 401 || status == 403) {
                return FailureKind.FATAL;
            }
            return status == 408 || status == 429 || status >= 500 ? FailureKind.TRANSIENT : FailureKind.MODEL_SPECIFIC;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException || cause instanceof UncheckedIOException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return FailureKind.TRANSIENT;
            }
        }
        return FailureKind.FATAL; // a bug on our side; trying other models would only hide it
    }

    private static void record(LlmRoute route, Outcome outcome, String error, long elapsedMs, TokenUsage usage) {
        int input = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int output = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        int cacheRead = 0;
        int cacheWrite = 0;
        if (usage instanceof AnthropicTokenUsage anthropic) {
            // Anthropic reports cached tokens apart from input_tokens.
            cacheRead = anthropic.cacheReadInputTokens() == null ? 0 : anthropic.cacheReadInputTokens();
            cacheWrite = anthropic.cacheCreationInputTokens() == null ? 0 : anthropic.cacheCreationInputTokens();
        } else if (usage instanceof OpenAiTokenUsage openAi && openAi.inputTokensDetails() != null
                && openAi.inputTokensDetails().cachedTokens() != null) {
            // OpenAI-compatible APIs count cached tokens inside prompt_tokens: split them out so both look the same.
            cacheRead = openAi.inputTokensDetails().cachedTokens();
            input = Math.max(0, input - cacheRead);
        }
        LlmCallLog.record(new LlmCall(route.name(), route.model(), outcome, error, elapsedMs, input, output, cacheRead,
                cacheWrite, route.cost(input, output, cacheRead, cacheWrite)));
    }

    private static String summary(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
