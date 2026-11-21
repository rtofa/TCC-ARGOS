package br.com.argos.argos_api.ai.llm;

import br.com.argos.argos_api.ai.llm.LlmCallLog.LlmCall;
import br.com.argos.argos_api.ai.llm.LlmCallLog.Outcome;
import br.com.argos.argos_api.shared.resilience.CircuitBreaker;
import br.com.argos.argos_api.shared.resilience.ResilientCall;
import br.com.argos.argos_api.shared.resilience.RetryPolicy;
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
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM routing for API failures: sends each request down an ordered chain of models (primary, then fallbacks, which
 * may belong to different providers).
 *
 * <ul>
 *   <li>Transient failures (timeout, 5xx, overload, network) are retried with exponential backoff on the same model;
 *       when the retries run out the model's circuit breaker counts a failure and the next model is tried.</li>
 *   <li>A per-minute quota (429 with "try again in Xs") waits for the quota when the wait fits the budget, otherwise
 *       moves on at once.</li>
 *   <li>Model-specific failures (model unavailable, request rejected by that model, refusal) move on without
 *       counting against the model's health.</li>
 *   <li>Authentication failures stop immediately: a wrong key is a configuration error to surface, not to hide.</li>
 * </ul>
 *
 * It is a plain LangChain4j {@link ChatModel} (so AI services, tools and structured output run on top of it) and also
 * streams: {@link #stream} fails over only while no token has reached the caller.
 */
public class LlmRouter implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    public record RouteStatus(String name, String model, CircuitBreaker.State state, boolean streaming) {
    }

    /** Part of the answer had already been streamed when the model failed, so it cannot move to another model. */
    public static class PartialResponseException extends RuntimeException {
        public PartialResponseException(String model, Throwable cause) {
            super("O modelo " + model + " falhou no meio da resposta", cause);
        }
    }

    private enum FailureKind { TRANSIENT, MODEL_SPECIFIC, FATAL }

    @FunctionalInterface
    private interface Attempt {
        ChatResponse run(LlmRoute route, AtomicBoolean emitted);
    }

    /** "Please try again in 17.3s" (Groq/OpenAI) or "retryDelay": "17s" (Gemini). */
    private static final Pattern RETRY_AFTER = Pattern.compile(
            "(?i)(?:try again in|retry in|retrydelay\"?\\s*[:=]\\s*\"?)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(ms|s|m)?");

    private final String purpose;
    private final List<LlmRoute> routes;
    private final Duration maxRateLimitWait;
    private final RetryPolicy retryPolicy;
    private final Duration streamTimeout;
    private final ResilientCall.Sleeper sleeper;

    public LlmRouter(String purpose, List<LlmRoute> routes) {
        this(purpose, routes, Duration.ZERO);
    }

    public LlmRouter(String purpose, List<LlmRoute> routes, Duration maxRateLimitWait) {
        this(purpose, routes, maxRateLimitWait, RetryPolicy.NONE, Duration.ofSeconds(90), d -> Thread.sleep(d.toMillis()));
    }

    /**
     * @param maxRateLimitWait how long one request may wait for a per-minute quota to free up before moving on
     * @param retryPolicy      backoff for transient failures on the same model, before failing over
     * @param streamTimeout    longest a streamed answer may take from request to last token
     */
    public LlmRouter(String purpose, List<LlmRoute> routes, Duration maxRateLimitWait, RetryPolicy retryPolicy,
                     Duration streamTimeout, ResilientCall.Sleeper sleeper) {
        this.purpose = purpose;
        this.routes = List.copyOf(routes);
        this.maxRateLimitWait = maxRateLimitWait;
        this.retryPolicy = retryPolicy;
        this.streamTimeout = streamTimeout;
        this.sleeper = sleeper;
    }

    public boolean available() {
        return !routes.isEmpty();
    }

    public List<RouteStatus> status() {
        return routes.stream()
                .map(route -> new RouteStatus(route.name(), route.model(), route.breaker().state(),
                        route.streamingModel() != null))
                .toList();
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return route((route, emitted) -> route.chatModel().chat(request), route -> true);
    }

    /**
     * Streams the answer of the first healthy model. Tokens are handed to {@code onToken} as they arrive (on the HTTP
     * client's thread, so the consumer must be thread-safe); the method blocks until the answer is complete.
     *
     * @throws PartialResponseException when the model fails after some tokens were already delivered
     * @throws AllRoutesFailedException when no model could answer
     */
    public ChatResponse stream(ChatRequest request, Consumer<String> onToken) {
        return route((route, emitted) -> streamOnce(route, request, onToken, emitted),
                route -> route.streamingModel() != null);
    }

    private ChatResponse streamOnce(LlmRoute route, ChatRequest request, Consumer<String> onToken, AtomicBoolean emitted) {
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        route.streamingModel().chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                if (token == null || token.isEmpty() || done.isDone()) {
                    return;
                }
                emitted.set(true);
                onToken.accept(token);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        try {
            return done.get(streamTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause.getMessage(), cause);
        } catch (java.util.concurrent.TimeoutException e) {
            done.cancel(true);
            throw new IllegalStateException("O modelo não terminou a resposta em " + streamTimeout.toSeconds() + " s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompido enquanto aguardava o modelo", e);
        }
    }

    private ChatResponse route(Attempt attempt, Predicate<LlmRoute> eligible) {
        List<String> failures = new ArrayList<>();
        RuntimeException lastError = null;
        for (LlmRoute route : routes) {
            if (!eligible.test(route)) {
                continue;
            }
            if (!route.breaker().tryAcquire()) {
                failures.add(route.name() + ": circuito aberto");
                LlmCallLog.record(new LlmCall(route.name(), route.model(), Outcome.SKIPPED, "circuito aberto", 0,
                        0, 0, 0, 0, BigDecimal.ZERO));
                continue;
            }
            Duration waited = Duration.ZERO;
            int failedAttempts = 0;
            while (true) {
                AtomicBoolean emitted = new AtomicBoolean(false);
                long started = System.nanoTime();
                try {
                    ChatResponse response = attempt.run(route, emitted);
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
                    FailureKind kind = classify(error);
                    if (emitted.get()) {
                        // The caller already shows part of this answer: switching models would splice two answers.
                        if (kind == FailureKind.TRANSIENT) {
                            route.breaker().onFailure();
                        } else {
                            route.breaker().onNeutral();
                        }
                        record(route, Outcome.FAILED, "falha no meio do streaming: " + summary(error), elapsed, null);
                        throw new PartialResponseException(route.model(), error);
                    }
                    // A per-minute quota is not an outage: when the provider says how long to wait and we can afford
                    // it, wait and retry the same (preferred) model instead of degrading to the next one.
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
                    failedAttempts++;
                    if (kind == FailureKind.TRANSIENT && !isRateLimit(error) && failedAttempts < retryPolicy.maxAttempts()) {
                        Duration delay = retryPolicy.delayBeforeRetry(failedAttempts, ThreadLocalRandom.current()::nextDouble);
                        record(route, Outcome.FAILED, "transitória, nova tentativa em " + delay.toMillis() + " ms: "
                                + summary(error), elapsed, null);
                        log.info("[{}] modelo {} falhou de forma transitória, nova tentativa em {} ms", purpose,
                                route.model(), delay.toMillis());
                        sleep(delay);
                        continue;
                    }
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

    /** Whether a failure of an AI provider (chat, embeddings, rerank) is worth retrying. */
    public static boolean isTransient(Throwable error) {
        return classify(error) == FailureKind.TRANSIENT;
    }

    static Optional<Duration> retryAfter(Throwable error) {
        if (!isRateLimit(error) || error.getMessage() == null) {
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

    private static boolean isRateLimit(Throwable error) {
        return error instanceof RateLimitException
                || (error instanceof HttpException http && http.statusCode() == 429)
                || (error.getMessage() != null && error.getMessage().toLowerCase().contains("rate limit"));
    }

    private void sleep(Duration pause) {
        try {
            sleeper.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompido enquanto aguardava o modelo", e);
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
