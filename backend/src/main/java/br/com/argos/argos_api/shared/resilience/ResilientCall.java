package br.com.argos.argos_api.shared.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runs a call to an external dependency behind its circuit breaker, retrying transient failures with backoff.
 *
 * <ul>
 *   <li>Circuit open: fails fast with {@link CircuitOpenException}, without touching the dependency.</li>
 *   <li>Transient failure (timeout, 429, 5xx, network): retried per {@link RetryPolicy}; when the attempts run out the
 *       breaker counts one failure.</li>
 *   <li>Any other failure (bad request, bug): rethrown at once and not counted against the dependency's health.</li>
 * </ul>
 *
 * Timeouts are set on each client (HTTP read/connect timeouts), so a slow call ends as a transient failure here.
 */
public final class ResilientCall {

    /** Blocks the calling thread; cheap on virtual threads. Replaced in tests to keep them fast. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(ResilientCall.class);

    private final String dependency;
    private final CircuitBreaker breaker;
    private final RetryPolicy retryPolicy;
    private final Predicate<Throwable> transientError;
    private final Sleeper sleeper;

    public ResilientCall(String dependency, CircuitBreaker breaker, RetryPolicy retryPolicy,
                         Predicate<Throwable> transientError) {
        this(dependency, breaker, retryPolicy, transientError, d -> Thread.sleep(d.toMillis()));
    }

    public ResilientCall(String dependency, CircuitBreaker breaker, RetryPolicy retryPolicy,
                         Predicate<Throwable> transientError, Sleeper sleeper) {
        this.dependency = Objects.requireNonNull(dependency);
        this.breaker = Objects.requireNonNull(breaker);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.transientError = Objects.requireNonNull(transientError);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    public <T> T call(Supplier<T> action) {
        if (!breaker.tryAcquire()) {
            throw new CircuitOpenException(dependency);
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            try {
                T result = action.get();
                breaker.onSuccess();
                return result;
            } catch (RuntimeException error) {
                last = error;
                if (!transientError.test(error)) {
                    breaker.onNeutral();
                    throw error;
                }
                if (attempt == retryPolicy.maxAttempts()) {
                    break;
                }
                Duration delay = retryPolicy.delayBeforeRetry(attempt, ThreadLocalRandom.current()::nextDouble);
                log.atWarn().setMessage("Falha transitória; nova tentativa com backoff")
                        .addKeyValue("dependency", dependency)
                        .addKeyValue("attempt", attempt)
                        .addKeyValue("delayMs", delay.toMillis())
                        .addKeyValue("error", summary(error))
                        .log();
                pause(delay, error);
            }
        }
        breaker.onFailure();
        throw last;
    }

    public CircuitBreaker.State state() {
        return breaker.state();
    }

    public String dependency() {
        return dependency;
    }

    private void pause(Duration delay, RuntimeException cause) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            breaker.onNeutral();
            throw cause;
        }
    }

    private static String summary(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }
}
