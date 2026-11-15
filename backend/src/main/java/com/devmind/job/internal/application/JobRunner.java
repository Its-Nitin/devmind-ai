package com.devmind.job.internal.application;

import com.devmind.job.JobHandler;
import com.devmind.job.JobOutcome;
import com.devmind.job.JobOutcome.Failure;
import com.devmind.job.JobOutcome.RetryableFailure;
import com.devmind.job.JobOutcome.Succeeded;
import com.devmind.job.JobStatus;
import com.devmind.job.JobType;
import com.devmind.job.internal.domain.RetryBackoff;
import com.devmind.job.internal.persistence.JobQueries;
import com.devmind.job.internal.persistence.JobRecord;
import com.devmind.job.internal.persistence.Lease;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Claims jobs and runs one attempt each (ADR-0009). The worker calls it; tests call it directly. Not transactional:
 * every statement is atomic on its own, and handlers open their own transactions (SPRING-2).
 */
@Service
class JobRunner {

    static final String MDC_JOB_ID = "jobId";
    static final String UNEXPECTED_ERROR = "unexpected-error";
    static final String LEASE_EXPIRED = "lease-expired";

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobQueries jobs;
    private final Map<JobType, JobHandler> handlers;
    private final JobProperties properties;
    private final RetryBackoff backoff;
    private final Clock clock;
    private final MeterRegistry meters;

    JobRunner(JobQueries jobs, List<JobHandler> handlers, JobProperties properties, Clock clock, MeterRegistry meters) {
        this.jobs = jobs;
        this.handlers = byType(handlers);
        this.properties = properties;
        this.backoff = new RetryBackoff(properties.retryBaseDelay(), properties.retryMaxDelay());
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Claims the oldest due job that has a handler, for {@code workerId}. Returns empty when nothing is due or no
     * handler is registered.
     */
    Optional<RunningJob> claimNext(String workerId) {
        if (handlers.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return jobs.claimNext(handlers.keySet(), workerId, now, now.plus(properties.leaseDuration()))
                .map(job ->
                        new RunningJob(job, new Lease(job.id(), workerId, job.attempts()), jobs, clock, properties));
    }

    /** Fails jobs that lost their worker on their last attempt; they can't be claimed again. Returns how many. */
    int failAbandoned() {
        int failed = jobs.failExpiredLastAttempts(
                clock.instant(), LEASE_EXPIRED, "The job stopped responding on its last attempt.");
        if (failed > 0) {
            log.warn("Failed {} job(s) whose lease expired on the last attempt", failed);
        }
        return failed;
    }

    /** Runs the handler for one claimed attempt and records the outcome. Never throws for a handler failure. */
    void run(RunningJob attempt) {
        JobRecord job = attempt.job();
        try (var _ = MDC.putCloseable(MDC_JOB_ID, job.publicId().value().toString())) { // OBS-1
            log.info("Job started: type={}, attempt={}/{}", job.type(), attempt.attempt(), job.maxAttempts());
            JobOutcome outcome = invoke(handlerFor(job.type()), attempt);
            record(attempt, outcome);
        }
    }

    /** JAVA-8 boundary: a handler's exception becomes a retryable failure; its message isn't stored (ERR-5). */
    private static JobOutcome invoke(JobHandler handler, RunningJob attempt) {
        try {
            return Objects.requireNonNull(handler.handle(attempt), "handler returned null");
        } catch (RuntimeException ex) {
            log.error("Job handler failed unexpectedly", ex);
            return JobOutcome.retryable(
                    UNEXPECTED_ERROR,
                    "The job failed unexpectedly (" + ex.getClass().getSimpleName() + "). It will be retried.");
        }
    }

    /** A retryable failure with attempts left is requeued after a backoff; every other failure is final (ERR-3). */
    private void record(RunningJob attempt, JobOutcome outcome) {
        Lease lease = attempt.lease();
        Instant now = clock.instant();
        switch (outcome) {
            case Succeeded _ -> {
                if (applied(jobs.markSucceeded(lease, now), attempt, JobStatus.SUCCEEDED)) {
                    log.info("Job succeeded");
                }
            }
            case RetryableFailure failure
            when lease.attempt() < attempt.job().maxAttempts() -> {
                Instant runAfter = now.plus(backoff.delayAfter(lease.attempt(), ThreadLocalRandom.current()));
                boolean written = jobs.requeue(lease, runAfter, failure.code(), failure.detail(), now);
                if (applied(written, attempt, JobStatus.QUEUED)) {
                    log.warn("Job attempt failed, retrying at {}: code={}", runAfter, failure.code());
                }
            }
            case Failure failure -> {
                boolean written = jobs.markFailed(lease, failure.code(), failure.detail(), now);
                if (applied(written, attempt, JobStatus.FAILED)) {
                    log.warn("Job failed: code={}", failure.code());
                }
            }
        }
    }

    /** Counts the transition (OBS-4), or reports that the lease was lost and this attempt's outcome discarded. */
    private boolean applied(boolean written, RunningJob attempt, JobStatus status) {
        if (!written) {
            log.warn("Job lease was lost before the attempt finished; {} discarded", status);
            return false;
        }
        meters.counter("devmind.jobs", "type", attempt.job().type().name(), "status", status.name())
                .increment();
        return true;
    }

    private JobHandler handlerFor(JobType type) {
        // claimNext only claims types that have a handler.
        return Objects.requireNonNull(handlers.get(type), () -> "no handler for " + type);
    }

    private static Map<JobType, JobHandler> byType(List<JobHandler> handlers) {
        Map<JobType, JobHandler> byType = new EnumMap<>(JobType.class);
        for (JobHandler handler : handlers) {
            JobHandler previous = byType.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("Two handlers for job type " + handler.type() + ": "
                        + previous.getClass().getName() + " and "
                        + handler.getClass().getName());
            }
        }
        return byType;
    }
}
