package br.com.argos.argos_api.ai.llm;

import br.com.argos.argos_api.ai.llm.LlmCallLog.LlmCall;
import br.com.argos.argos_api.ai.llm.LlmCallLog.Outcome;
import br.com.argos.argos_api.shared.resilience.CircuitBreaker;
import br.com.argos.argos_api.shared.resilience.RetryPolicy;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRouterTest {

    private static final ChatRequest REQUEST = ChatRequest.builder().messages(UserMessage.from("oi")).build();

    @BeforeEach
    void startLog() {
        LlmCallLog.start();
    }

    @AfterEach
    void stopLog() {
        LlmCallLog.stop();
    }

    @Test
    void transientFailureFallsBackToTheNextModel() {
        LlmRouter router = new LlmRouter("teste", List.of(
                route("primario", failing(new InternalServerException("overloaded"))),
                route("reserva", answering("resposta da reserva"))));

        ChatResponse response = router.chat(REQUEST);

        assertThat(response.aiMessage().text()).isEqualTo("resposta da reserva");
        List<LlmCall> calls = LlmCallLog.stop();
        assertThat(calls).extracting(LlmCall::route).containsExactly("primario", "reserva");
        assertThat(calls).extracting(LlmCall::outcome).containsExactly(Outcome.FAILED, Outcome.OK);
        assertThat(calls.get(1).costUsd()).isPositive();
    }

    @Test
    void repeatedFailuresOpenTheCircuitAndTheModelIsSkipped() {
        AtomicInteger primaryCalls = new AtomicInteger();
        ChatModel flaky = failing(new RateLimitException("429"));
        ChatModel counting = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                primaryCalls.incrementAndGet();
                return flaky.chat(request);
            }
        };
        LlmRouter router = new LlmRouter("teste", List.of(route("primario", counting), route("reserva", answering("ok"))));

        for (int i = 0; i < 5; i++) {
            router.chat(REQUEST);
        }

        assertThat(primaryCalls).as("breaker opens after 3 failures").hasValue(3);
        assertThat(router.status().get(0).state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(LlmCallLog.stop()).extracting(LlmCall::outcome).contains(Outcome.SKIPPED);
    }

    @Test
    void authenticationFailureStopsTheChain() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        LlmRouter router = new LlmRouter("teste", List.of(
                route("primario", failing(new AuthenticationException("invalid x-api-key"))),
                route("reserva", model(request -> {
                    fallbackCalls.incrementAndGet();
                    return answer("não deveria");
                }))));

        assertThatThrownBy(() -> router.chat(REQUEST)).isInstanceOf(AuthenticationException.class);
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    void refusalMovesOnWithoutTrippingTheBreaker() {
        ChatModel refusing = model(request -> ChatResponse.builder()
                .aiMessage(AiMessage.from(""))
                .finishReason(FinishReason.OTHER)
                .build());
        LlmRouter router = new LlmRouter("teste", List.of(route("primario", refusing), route("reserva", answering("ok"))));

        assertThat(router.chat(REQUEST).aiMessage().text()).isEqualTo("ok");
        assertThat(router.status().get(0).state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(LlmCallLog.stop()).extracting(LlmCall::outcome).containsExactly(Outcome.REFUSED, Outcome.OK);
    }

    @Test
    void whenEveryModelFailsTheCallerGetsAllTheReasons() {
        LlmRouter router = new LlmRouter("teste", List.of(
                route("primario", failing(new InternalServerException("500"))),
                route("reserva", failing(new InvalidRequestException("modelo não aceita o parâmetro")))));

        assertThatThrownBy(() -> router.chat(REQUEST))
                .isInstanceOf(AllRoutesFailedException.class)
                .satisfies(e -> assertThat(((AllRoutesFailedException) e).failures()).hasSize(2));
    }

    @Test
    void perMinuteQuotaWaitsAndRetriesTheSameModelInsteadOfFallingBack() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel limitedOnce = model(request -> calls.incrementAndGet() == 1
                ? failWith(new RateLimitException("Rate limit reached for model `openai/gpt-oss-120b`: tokens per minute "
                        + "(TPM): Limit 8000, Used 7523, Requested 2786. Please try again in 0.05s."))
                : answer("ok do principal"));
        LlmRouter router = new LlmRouter("teste", List.of(route("primario", limitedOnce), route("reserva", answering("reserva"))),
                Duration.ofSeconds(2));

        assertThat(router.chat(REQUEST).aiMessage().text()).isEqualTo("ok do principal");
        assertThat(LlmCallLog.stop()).extracting(LlmCall::outcome).containsExactly(Outcome.RATE_LIMITED, Outcome.OK);
        assertThat(router.status().get(0).state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aWaitLongerThanTheBudgetMovesToTheNextModel() {
        LlmRouter router = new LlmRouter("teste", List.of(
                route("primario", failing(new RateLimitException("Rate limit reached. Please try again in 30s."))),
                route("reserva", answering("reserva"))), Duration.ofSeconds(2));

        assertThat(router.chat(REQUEST).aiMessage().text()).isEqualTo("reserva");
        assertThat(LlmRouter.retryAfter(new RateLimitException("\"retryDelay\": \"17s\""))).contains(Duration.ofSeconds(17));
    }

    @Test
    void routerWithoutModelsIsUnavailable() {
        LlmRouter router = new LlmRouter("teste", List.of());

        assertThat(router.available()).isFalse();
        assertThatThrownBy(() -> router.chat(REQUEST)).isInstanceOf(AllRoutesFailedException.class);
    }

    @Test
    void transientFailureIsRetriedWithBackoffOnTheSameModelBeforeFailingOver() {
        AtomicInteger calls = new AtomicInteger();
        List<Duration> sleeps = new ArrayList<>();
        ChatModel flakyOnce = model(request -> calls.incrementAndGet() == 1
                ? failWith(new InternalServerException("503 service unavailable"))
                : answer("ok do principal"));
        LlmRouter router = new LlmRouter("teste", List.of(route("primario", flakyOnce), route("reserva", answering("reserva"))),
                Duration.ZERO, RetryPolicy.exponential(2, Duration.ofMillis(100), Duration.ofSeconds(1)),
                Duration.ofSeconds(5), sleeps::add);

        assertThat(router.chat(REQUEST).aiMessage().text()).isEqualTo("ok do principal");
        assertThat(sleeps).hasSize(1);
        assertThat(LlmCallLog.stop()).extracting(LlmCall::route).containsExactly("primario", "primario");
    }

    @Test
    void streamingFailsOverWhileNoTokenReachedTheCaller() {
        List<String> tokens = new CopyOnWriteArrayList<>();
        LlmRouter router = new LlmRouter("teste", List.of(
                streamingRoute("primario", streaming(List.of(), new InternalServerException("overloaded"))),
                streamingRoute("reserva", streaming(List.of("Olá", ", mundo"), null))));

        ChatResponse response = router.stream(REQUEST, tokens::add);

        assertThat(tokens).containsExactly("Olá", ", mundo");
        assertThat(response.aiMessage().text()).isEqualTo("Olá, mundo");
        assertThat(LlmCallLog.stop()).extracting(LlmCall::outcome).containsExactly(Outcome.FAILED, Outcome.OK);
    }

    @Test
    void aStreamThatBreaksMidAnswerIsNotSplicedWithAnotherModel() {
        List<String> tokens = new CopyOnWriteArrayList<>();
        AtomicInteger fallbackCalls = new AtomicInteger();
        StreamingChatModel counting = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                fallbackCalls.incrementAndGet();
                handler.onCompleteResponse(answer("não deveria"));
            }
        };
        LlmRouter router = new LlmRouter("teste", List.of(
                streamingRoute("primario", streaming(List.of("Primeira parte"), new InternalServerException("conexão caiu"))),
                streamingRoute("reserva", counting)));

        assertThatThrownBy(() -> router.stream(REQUEST, tokens::add)).isInstanceOf(LlmRouter.PartialResponseException.class);
        assertThat(tokens).containsExactly("Primeira parte");
        assertThat(fallbackCalls).hasValue(0);
    }

    private static LlmRoute streamingRoute(String name, StreamingChatModel model) {
        return new LlmRoute(name, "modelo-" + name, failing(new IllegalStateException("só streaming")), model,
                new CircuitBreaker(3, Duration.ofMinutes(1), Clock.systemUTC()), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO);
    }

    private static StreamingChatModel streaming(List<String> tokens, RuntimeException errorAfterTokens) {
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                tokens.forEach(handler::onPartialResponse);
                if (errorAfterTokens != null) {
                    handler.onError(errorAfterTokens);
                } else {
                    handler.onCompleteResponse(answer(String.join("", tokens)));
                }
            }
        };
    }

    private static LlmRoute route(String name, ChatModel model) {
        return new LlmRoute(name, "modelo-" + name, model, new CircuitBreaker(3, Duration.ofMinutes(1), Clock.systemUTC()),
                new BigDecimal("4.00"), new BigDecimal("20.00"), new BigDecimal("0.20"));
    }

    private static ChatModel model(Function<ChatRequest, ChatResponse> behaviour) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return behaviour.apply(request);
            }
        };
    }

    private static ChatModel failing(RuntimeException error) {
        return model(request -> {
            throw error;
        });
    }

    private static ChatModel answering(String text) {
        return model(request -> answer(text));
    }

    private static ChatResponse failWith(RuntimeException error) {
        throw error;
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .finishReason(FinishReason.STOP)
                .tokenUsage(new TokenUsage(100, 20))
                .build();
    }
}
