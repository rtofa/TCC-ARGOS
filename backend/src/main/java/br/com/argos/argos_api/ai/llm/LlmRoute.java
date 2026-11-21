package br.com.argos.argos_api.ai.llm;

import br.com.argos.argos_api.shared.resilience.CircuitBreaker;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One model in the fallback chain, with its health and prices (USD per million tokens).
 *
 * @param streamingModel the same model with token streaming; {@code null} when only blocking calls are wired
 */
public record LlmRoute(String name, String model, ChatModel chatModel, StreamingChatModel streamingModel,
                       CircuitBreaker breaker, BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion,
                       BigDecimal cacheReadPricePerMillion) {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    /** Writing a prompt prefix into the cache costs 1.25x the input price. */
    private static final BigDecimal CACHE_WRITE_FACTOR = new BigDecimal("1.25");

    public LlmRoute(String name, String model, ChatModel chatModel, CircuitBreaker breaker,
                    BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion,
                    BigDecimal cacheReadPricePerMillion) {
        this(name, model, chatModel, null, breaker, inputPricePerMillion, outputPricePerMillion, cacheReadPricePerMillion);
    }

    public BigDecimal cost(int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens) {
        BigDecimal total = inputPricePerMillion.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputPricePerMillion.multiply(BigDecimal.valueOf(outputTokens)))
                .add(cacheReadPricePerMillion.multiply(BigDecimal.valueOf(cacheReadTokens)))
                .add(inputPricePerMillion.multiply(CACHE_WRITE_FACTOR).multiply(BigDecimal.valueOf(cacheWriteTokens)));
        return total.divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
