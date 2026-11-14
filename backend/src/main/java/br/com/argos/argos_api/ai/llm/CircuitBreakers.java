package br.com.argos.argos_api.ai.llm;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One breaker per model, shared by every router, so an outage seen by the assistant also protects investigations. */
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
        return byModel.computeIfAbsent(model, ignored -> new CircuitBreaker(failureThreshold, openDuration, clock));
    }
}
