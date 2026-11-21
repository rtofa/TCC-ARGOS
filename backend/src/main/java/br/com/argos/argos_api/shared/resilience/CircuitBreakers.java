package br.com.argos.argos_api.shared.resilience;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One breaker per dependency (an LLM model, the embedding API, the reranker, the search cluster), shared by every caller,
 * so an outage seen by one request protects the next ones.
 */
public final class CircuitBreakers {

    private final Map<String, CircuitBreaker> byModel = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    public CircuitBreakers(int failureThreshold, Duration openDuration, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    public CircuitBreaker forModel(String model) {
        return forDependency(model);
    }

    public CircuitBreaker forDependency(String name) {
        return byModel.computeIfAbsent(name, ignored -> new CircuitBreaker(failureThreshold, openDuration, clock));
    }
}
