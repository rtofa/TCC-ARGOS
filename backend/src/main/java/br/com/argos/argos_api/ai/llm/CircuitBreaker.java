package br.com.argos.argos_api.ai.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Per-model circuit breaker. After {@code failureThreshold} consecutive transient failures the model is skipped for
 * {@code openDuration}; then a single trial request decides whether it closes again.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean trialInFlight;

    public CircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration;
        this.clock = clock;
    }

    /** Whether a request may go to this model now; in half-open state only one trial goes through. */
    public synchronized boolean tryAcquire() {
        if (state == State.OPEN) {
            if (clock.instant().isBefore(openedAt.plus(openDuration))) {
                return false;
            }
            state = State.HALF_OPEN;
            trialInFlight = false;
        }
        if (state == State.HALF_OPEN) {
            if (trialInFlight) {
                return false;
            }
            trialInFlight = true;
        }
        return true;
    }

    public synchronized void onSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        trialInFlight = false;
    }

    /** A transient failure (timeout, overload, 5xx) that says something about the model's health. */
    public synchronized void onFailure() {
        trialInFlight = false;
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = clock.instant();
        }
    }

    /** A failure unrelated to the model's health (bad request, refusal): releases the trial, keeps the counters. */
    public synchronized void onNeutral() {
        trialInFlight = false;
    }

    public synchronized State state() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration))) {
            return State.HALF_OPEN;
        }
        return state;
    }
}
