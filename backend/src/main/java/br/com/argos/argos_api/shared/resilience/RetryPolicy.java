package br.com.argos.argos_api.shared.resilience;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with jitter for calls to external services.
 *
 * <p>The delay before retry {@code n} is {@code initialDelay × multiplier^(n-1)}, capped at {@code maxDelay} and spread
 * by ±{@code jitter} so clients that failed together do not retry together.
 *
 * @param maxAttempts total attempts, the first one included (1 = no retry)
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay, double jitter) {

    public static final RetryPolicy NONE = new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO, 0.0);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts deve ser >= 1");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier deve ser >= 1");
        }
        if (jitter < 0.0 || jitter > 1.0) {
            throw new IllegalArgumentException("jitter deve estar entre 0 e 1");
        }
        if (initialDelay == null || initialDelay.isNegative() || maxDelay == null || maxDelay.isNegative()) {
            throw new IllegalArgumentException("delays não podem ser negativos");
        }
    }

    /** Doubles the delay after each failure, with 20% jitter. */
    public static RetryPolicy exponential(int maxAttempts, Duration initialDelay, Duration maxDelay) {
        return new RetryPolicy(maxAttempts, initialDelay, 2.0, maxDelay, 0.2);
    }

    /**
     * @param retry  1 for the first retry (after the first failure), 2 for the second…
     * @param random uniform values in [0, 1)
     */
    public Duration delayBeforeRetry(int retry, DoubleSupplier random) {
        double base = initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, retry - 1));
        double capped = Math.min(base, maxDelay.toMillis());
        double spread = capped * jitter;
        double value = capped - spread + random.getAsDouble() * 2 * spread;
        return Duration.ofMillis(Math.max(0, Math.round(value)));
    }
}
