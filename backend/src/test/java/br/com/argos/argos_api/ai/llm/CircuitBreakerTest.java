package br.com.argos.argos_api.ai.llm;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-14T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(60), clock);

    @Test
    void opensAfterConsecutiveTransientFailures() {
        breaker.onFailure();
        breaker.onFailure();
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void successResetsTheFailureCount() {
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void afterTheCooldownLetsOneTrialThroughAndClosesOnSuccess() {
        openBreaker();
        clock.advance(Duration.ofSeconds(61));

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.tryAcquire()).as("only one trial at a time").isFalse();

        breaker.onSuccess();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void aFailedTrialOpensTheCircuitAgain() {
        openBreaker();
        clock.advance(Duration.ofSeconds(61));
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void neutralOutcomesDoNotCountAgainstTheModel() {
        breaker.onFailure();
        breaker.onFailure();
        breaker.onNeutral();
        breaker.onNeutral();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private void openBreaker() {
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
    }
}
