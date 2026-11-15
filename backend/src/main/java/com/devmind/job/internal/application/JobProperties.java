package com.devmind.job.internal.application;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Worker and retry tuning, {@code devmind.job.*} (SPRING-1, GEN-6). Defaults are in {@code application.yaml}.
 *
 * @param workerEnabled whether the worker starts with the application; off in tests, which drive it directly
 * @param pollInterval wait between claim attempts while the queue is empty
 * @param maxConcurrent jobs run at once by this instance
 * @param leaseDuration how long a claim or heartbeat holds a job; a crashed worker's job is reclaimed after this
 * @param maxAttempts attempts per job, including the first
 * @param retryBaseDelay backoff after the first failed attempt (doubles per attempt, with jitter, ERR-3)
 * @param retryMaxDelay cap on the backoff
 * @param progressInterval minimum time between progress writes of one job
 * @param shutdownTimeout how long stopping waits for running handlers; their leases then expire and they are
 *     reclaimed
 */
@Validated
@ConfigurationProperties("devmind.job")
record JobProperties(
        boolean workerEnabled,
        @NotNull @DurationMin(millis = 10) Duration pollInterval,
        @Min(1) @Max(32) int maxConcurrent,
        @NotNull @DurationMin(seconds = 1) Duration leaseDuration,
        @Min(1) @Max(10) int maxAttempts,
        @NotNull @DurationMin(millis = 1) Duration retryBaseDelay,
        @NotNull Duration retryMaxDelay,
        @NotNull @DurationMin(millis = 0) Duration progressInterval,
        @NotNull @DurationMin(millis = 0) Duration shutdownTimeout) {

    @AssertTrue(message = "retry-max-delay must not be shorter than retry-base-delay")
    boolean isRetryDelayRangeValid() {
        // Null when a @NotNull above already failed; that violation is the one to report.
        return retryBaseDelay == null || retryMaxDelay == null || retryMaxDelay.compareTo(retryBaseDelay) >= 0;
    }
}
