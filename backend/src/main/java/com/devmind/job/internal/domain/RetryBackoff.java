package com.devmind.job.internal.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Delay before a retry: exponential in the attempt number, capped, with "equal jitter" (ERR-3). The delay is uniform
 * in {@code [d/2, d]} for {@code d = min(max, base * 2^(attempt-1))}, so retries spread out but never fire at once.
 */
public final class RetryBackoff {

    private final Duration base;
    private final Duration max;

    /** @throws IllegalArgumentException if {@code base} isn't positive or {@code max} is shorter than {@code base} */
    public RetryBackoff(Duration base, Duration max) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(max, "max");
        if (!base.isPositive() || max.compareTo(base) < 0) {
            throw new IllegalArgumentException("need 0 < base <= max, got base=" + base + ", max=" + max);
        }
        this.base = base;
        this.max = max;
    }

    /**
     * Returns the delay after failed attempt number {@code attempt}.
     *
     * @param attempt the attempt that just failed, starting at 1
     * @param random source of jitter; pass {@code ThreadLocalRandom.current()} in production
     * @throws IllegalArgumentException if {@code attempt} is less than 1
     */
    public Duration delayAfter(int attempt, RandomGenerator random) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt starts at 1, got " + attempt);
        }
        long ceilingMillis = ceilingMillis(attempt);
        long half = ceilingMillis / 2;
        return Duration.ofMillis(half + random.nextLong(ceilingMillis - half + 1));
    }

    private long ceilingMillis(int attempt) {
        long baseMillis = base.toMillis();
        long maxMillis = max.toMillis();
        // A shift by numberOfLeadingZeros or more would overflow into the sign bit; such a delay is capped anyway.
        int doublings = attempt - 1;
        if (doublings >= Long.numberOfLeadingZeros(baseMillis)) {
            return maxMillis;
        }
        return Math.min(maxMillis, baseMillis << doublings);
    }
}
