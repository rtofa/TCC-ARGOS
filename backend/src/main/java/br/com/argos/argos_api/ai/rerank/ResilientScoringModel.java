package br.com.argos.argos_api.ai.rerank;

import br.com.argos.argos_api.shared.resilience.ResilientCall;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;

import java.util.List;

/**
 * Reranker (Cohere Rerank by default) behind a circuit breaker, with retries and backoff for transient failures.
 * Callers treat a failure as "no reranking" and keep the fused ranking.
 */
public class ResilientScoringModel implements ScoringModel {

    private final ScoringModel delegate;
    private final ResilientCall resilience;
    private final String modelName;

    public ResilientScoringModel(ScoringModel delegate, ResilientCall resilience, String modelName) {
        this.delegate = delegate;
        this.resilience = resilience;
        this.modelName = modelName;
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(List.of());
        }
        return resilience.call(() -> delegate.scoreAll(segments, query));
    }

    public String modelName() {
        return modelName;
    }
}
