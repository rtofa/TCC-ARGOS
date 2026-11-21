package br.com.argos.argos_api.ai.embedding;

import br.com.argos.argos_api.shared.resilience.ResilientCall;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

/**
 * Embedding model (OpenAI {@code text-embedding-3-small} by default) behind a circuit breaker, with retries and
 * backoff for transient failures. Request timeouts are set on the underlying HTTP client.
 */
public class ResilientEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final ResilientCall resilience;
    private final String modelName;
    private final int dimension;

    public ResilientEmbeddingModel(EmbeddingModel delegate, ResilientCall resilience, String modelName, int dimension) {
        this.delegate = delegate;
        this.resilience = resilience;
        this.modelName = modelName;
        this.dimension = dimension;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(List.of());
        }
        return resilience.call(() -> delegate.embedAll(segments));
    }

    @Override
    public Response<Embedding> embed(String text) {
        return resilience.call(() -> delegate.embed(text));
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        return resilience.call(() -> delegate.embed(segment));
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
