package br.com.argos.argos_api.shared.resilience;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientCallTest {

    private final List<Duration> sleeps = new ArrayList<>();

    @Test
    void transientFailuresAreRetriedWithGrowingBackoff() {
        AtomicInteger attempts = new AtomicInteger();
        ResilientCall call = call(RetryPolicy.exponential(3, Duration.ofMillis(100), Duration.ofSeconds(1)));

        String result = call.call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new UncheckedIOException(new java.io.IOException("connection reset"));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(1)).isGreaterThan(sleeps.get(0));
        assertThat(call.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void otherFailuresAreNotRetriedNorCountedAgainstTheDependency() {
        AtomicInteger attempts = new AtomicInteger();
        ResilientCall call = call(RetryPolicy.exponential(3, Duration.ofMillis(100), Duration.ofSeconds(1)));

        assertThatThrownBy(() -> call.call(() -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("pedido inválido");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(attempts).hasValue(1);
        assertThat(sleeps).isEmpty();
        assertThat(call.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void repeatedOutagesOpenTheCircuitAndCallsFailFast() {
        AtomicInteger attempts = new AtomicInteger();
        ResilientCall call = new ResilientCall("busca", new CircuitBreaker(2, Duration.ofMinutes(1), Clock.systemUTC()),
                RetryPolicy.NONE, error -> true, sleeps::add);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> call.call(() -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("fora do ar");
            })).isInstanceOf(IllegalStateException.class);
        }

        assertThatThrownBy(() -> call.call(() -> "não chega aqui"))
                .isInstanceOf(CircuitOpenException.class)
                .hasMessageContaining("busca");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void backoffIsCappedAndSpreadByJitter() {
        RetryPolicy policy = new RetryPolicy(10, Duration.ofMillis(100), 2.0, Duration.ofMillis(500), 0.2);

        assertThat(policy.delayBeforeRetry(1, () -> 0.5)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delayBeforeRetry(2, () -> 0.5)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.delayBeforeRetry(8, () -> 0.5)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delayBeforeRetry(8, () -> 0.0)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.delayBeforeRetry(8, () -> 0.999)).isLessThanOrEqualTo(Duration.ofMillis(600));
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO, 1, Duration.ZERO, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ResilientCall call(RetryPolicy policy) {
        return new ResilientCall("teste", new CircuitBreaker(3, Duration.ofMinutes(1), Clock.systemUTC()), policy,
                error -> error instanceof UncheckedIOException, sleeps::add);
    }
}
