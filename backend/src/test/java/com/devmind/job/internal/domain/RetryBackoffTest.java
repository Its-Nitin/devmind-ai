package com.devmind.job.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryBackoffTest {

    private static final RetryBackoff BACKOFF = new RetryBackoff(Duration.ofSeconds(10), Duration.ofMinutes(10));

    /** Jitter at its lowest: always draws 0. */
    private static final RandomGenerator LOWEST = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long bound) {
            return 0;
        }
    };

    /** Jitter at its highest: always draws {@code bound - 1}. */
    private static final RandomGenerator HIGHEST = new RandomGenerator() {
        @Override
        public long nextLong() {
            return Long.MAX_VALUE;
        }

        @Override
        public long nextLong(long bound) {
            return bound - 1;
        }
    };

    @ParameterizedTest
    @CsvSource({"1, 10", "2, 20", "3, 40", "6, 320", "7, 600", "40, 600"})
    void delayDoublesPerAttemptUpToTheCapWithJitterBetweenHalfAndFull(int attempt, long ceilingSeconds) {
        Duration ceiling = Duration.ofSeconds(ceilingSeconds);

        assertThat(BACKOFF.delayAfter(attempt, LOWEST)).isEqualTo(ceiling.dividedBy(2));
        assertThat(BACKOFF.delayAfter(attempt, HIGHEST)).isEqualTo(ceiling);
    }

    @Test
    void hugeAttemptNumbersStayAtTheCapInsteadOfOverflowing() {
        assertThat(BACKOFF.delayAfter(Integer.MAX_VALUE, HIGHEST)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsAttemptBelowOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> BACKOFF.delayAfter(0, LOWEST));
    }

    @Test
    void rejectsNonPositiveBaseOrCapBelowBase() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryBackoff(Duration.ZERO, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryBackoff(Duration.ofSeconds(2), Duration.ofSeconds(1)));
    }
}
