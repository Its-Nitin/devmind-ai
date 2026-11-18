package com.devmind.job.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.job.JobApi;
import com.devmind.job.JobContext;
import com.devmind.job.JobId;
import com.devmind.job.JobOutcome;
import com.devmind.job.JobType;
import com.devmind.job.internal.application.JobRows.JobRow;
import com.devmind.support.IntegrationTest;
import com.devmind.support.MutableClock;
import com.devmind.support.TestProjects;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Claiming, running and recording outcomes against real Postgres, with time under test control (ADR-0009). */
@IntegrationTest
@Import(JobTestConfiguration.class)
class JobRunnerTest {

    private static final String WORKER_A = "worker-a";
    private static final String WORKER_B = "worker-b";

    private final JobApi jobApi;
    private final JobRunner runner;
    private final ScriptedJobHandler handler;
    private final MutableClock clock;
    private final JobProperties properties;
    private final JobRows rows;
    private final long projectId;

    @Autowired
    JobRunnerTest(
            JobApi jobApi,
            JobRunner runner,
            ScriptedJobHandler handler,
            MutableClock clock,
            JobProperties properties,
            JdbcClient jdbc) {
        this.jobApi = jobApi;
        this.runner = runner;
        this.handler = handler;
        this.clock = clock;
        this.properties = properties;
        this.rows = new JobRows(jdbc);
        this.projectId = new TestProjects(jdbc).insertProject().id();
    }

    @BeforeEach
    void startFromAnEmptyQueue() {
        rows.deleteAll();
        handler.reset();
        clock.set(JobTestConfiguration.START);
    }

    @Test
    void runsHandlerWithPayloadAndMarksJobSucceeded() {
        // Given
        AtomicReference<Map<String, Object>> seenPayload = new AtomicReference<>();
        handler.willDo(context -> {
            seenPayload.set(context.payload());
            context.progress("parsing", 10, 10);
            return JobOutcome.succeeded();
        });
        JobId job = submit("k1");

        // When
        runner.run(claim(WORKER_A));

        // Then
        JobRow row = rows.get(job);
        assertThat(row.status()).isEqualTo("SUCCEEDED");
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.lockedBy()).isNull();
        assertThat(row.errorCode()).isNull();
        assertThat(row.progressPhase()).isEqualTo("parsing");
        assertThat(row.progressDone()).isEqualTo(10);
        assertThat(row.progressTotal()).isEqualTo(10);
        assertThat(seenPayload.get()).isEqualTo(Map.of("sha", "abc123"));
    }

    @Test
    void retriesRetryableFailureWithGrowingBackoffThenFailsAfterMaxAttempts() {
        // Given
        handler.willDo(context -> JobOutcome.retryable("source-unavailable", "The repository could not be read."));
        JobId job = submit("k1");
        Duration ceiling = properties.retryBaseDelay();

        for (int attempt = 1; attempt < properties.maxAttempts(); attempt++) {
            // When
            Instant failedAt = clock.instant();
            runner.run(claim(WORKER_A));

            // Then: queued again, due within [ceiling/2, ceiling] of the failure, and not claimable before that
            JobRow row = rows.get(job);
            assertThat(row.status()).isEqualTo("QUEUED");
            assertThat(row.attempts()).isEqualTo(attempt);
            assertThat(row.errorCode()).isEqualTo("source-unavailable");
            assertThat(row.runAfter()).isBetween(failedAt.plus(ceiling.dividedBy(2)), failedAt.plus(ceiling));
            assertThat(runner.claimNext(WORKER_A)).isEmpty();

            clock.set(row.runAfter());
            ceiling = ceiling.multipliedBy(2);
        }

        // When: the last attempt fails too
        runner.run(claim(WORKER_A));

        // Then
        JobRow row = rows.get(job);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.attempts()).isEqualTo(properties.maxAttempts());
        assertThat(row.errorCode()).isEqualTo("source-unavailable");
        assertThat(row.errorDetail()).isEqualTo("The repository could not be read.");
        assertThat(row.lockedBy()).isNull();
    }

    @Test
    void failsPermanentFailureWithoutRetrying() {
        // Given
        handler.willDo(context -> JobOutcome.permanent("invalid-ref", "The commit doesn't exist."));
        JobId job = submit("k1");

        // When
        runner.run(claim(WORKER_A));

        // Then
        JobRow row = rows.get(job);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.errorCode()).isEqualTo("invalid-ref");
    }

    @Test
    void retriesHandlerExceptionWithoutStoringItsMessage() {
        // Given
        handler.willDo(context -> {
            throw new IllegalStateException("password=hunter2 in /etc/app.conf");
        });
        JobId job = submit("k1");

        // When
        runner.run(claim(WORKER_A));

        // Then: ERR-5, the stored detail names the exception type only
        JobRow row = rows.get(job);
        assertThat(row.status()).isEqualTo("QUEUED");
        assertThat(row.errorCode()).isEqualTo(JobRunner.UNEXPECTED_ERROR);
        assertThat(row.errorDetail()).contains("IllegalStateException").doesNotContain("hunter2", "/etc");
    }

    @Test
    void concurrentWorkersNeverClaimTheSameJob() {
        // Given
        int jobCount = 20;
        List<JobId> submitted = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            submitted.add(submit("k" + i));
        }
        Queue<JobId> claimed = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);

        // When: four workers race to drain the queue
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < 4; w++) {
                String workerId = "worker-" + w;
                workers.execute(() -> {
                    awaitQuietly(start);
                    for (Optional<RunningJob> next = runner.claimNext(workerId);
                            next.isPresent();
                            next = runner.claimNext(workerId)) {
                        claimed.add(next.orElseThrow().jobId());
                    }
                });
            }
            start.countDown();
        }

        // Then
        assertThat(claimed).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(submitted);
    }

    @Test
    void reclaimsJobWhoseLeaseExpiredAndDiscardsTheStaleAttempt() {
        // Given: worker A claims, then goes quiet past its lease
        JobId job = submit("k1");
        RunningJob stale = claim(WORKER_A);
        clock.advance(properties.leaseDuration().plusSeconds(1));

        // When
        RunningJob current = claim(WORKER_B);

        // Then: B owns attempt 2; A can no longer extend or finish the job
        assertThat(current.jobId()).isEqualTo(job);
        assertThat(current.attempt()).isEqualTo(2);
        assertThat(stale.heartbeat()).isFalse();
        runner.run(stale);
        assertThat(rows.get(job).status()).isEqualTo("RUNNING");
        assertThat(rows.get(job).lockedBy()).isEqualTo(WORKER_B);

        runner.run(current);
        assertThat(rows.get(job).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void sameWorkerReclaimingItsOwnExpiredJobStillFencesTheOldAttempt() {
        // Given
        JobId job = submit("k1");
        RunningJob stale = claim(WORKER_A);
        clock.advance(properties.leaseDuration().plusSeconds(1));
        RunningJob current = claim(WORKER_A);

        // When
        handler.willDo(context -> JobOutcome.permanent("stale-attempt", "Should never be stored."));
        runner.run(stale);

        // Then: the attempt number, not the worker ID, decides
        assertThat(rows.get(job).status()).isEqualTo("RUNNING");
        assertThat(current.heartbeat()).isTrue();
    }

    @Test
    void heartbeatKeepsTheJobFromBeingReclaimed() {
        // Given
        submit("k1");
        RunningJob attempt = claim(WORKER_A);
        clock.advance(properties.leaseDuration().minusSeconds(1));

        // When
        boolean held = attempt.heartbeat();
        clock.advance(Duration.ofSeconds(2)); // past the original lease, within the extended one

        // Then
        assertThat(held).isTrue();
        assertThat(runner.claimNext(WORKER_B)).isEmpty();
    }

    @Test
    void failsJobWhoseLeaseExpiredOnTheLastAttempt() {
        // Given: every attempt but the last fails, then the last one's worker dies
        handler.willDo(context -> JobOutcome.retryable("flaky", "Try again."));
        JobId job = submit("k1");
        for (int attempt = 1; attempt < properties.maxAttempts(); attempt++) {
            runner.run(claim(WORKER_A));
            clock.set(rows.get(job).runAfter());
        }
        claim(WORKER_A);
        clock.advance(properties.leaseDuration().plusSeconds(1));

        // When
        int failed = runner.failAbandoned();

        // Then
        assertThat(failed).isEqualTo(1);
        JobRow row = rows.get(job);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.errorCode()).isEqualTo(JobRunner.LEASE_EXPIRED);
        assertThat(runner.claimNext(WORKER_B)).isEmpty();
    }

    @Test
    void throttlesProgressWritesButAlwaysWritesTheFinalReport() {
        // Given: the handler records what is stored after each report
        List<Long> stored = new ArrayList<>();
        handler.willDo(context -> {
            stored.add(report(context, 1));
            stored.add(report(context, 2)); // same instant: throttled
            clock.advance(properties.progressInterval());
            stored.add(report(context, 3));
            stored.add(report(context, 10)); // done == total: written despite the throttle
            return JobOutcome.succeeded();
        });
        submit("k1");

        // When
        runner.run(claim(WORKER_A));

        // Then
        assertThat(stored).containsExactly(1L, 1L, 3L, 10L);
    }

    @Test
    void claimsNothingOfATypeWithoutAHandler() {
        // Given: ANALYSIS gets its handler in M5 (INDEX has one since M2 step 7)
        jobApi.submit(projectId, JobType.ANALYSIS, "k1", Map.of());

        // When / Then
        assertThat(runner.claimNext(WORKER_A)).isEmpty();
    }

    /** Reports {@code done} of 10 and returns the {@code done} value now stored. */
    private long report(JobContext context, long done) {
        context.progress("parsing", done, 10);
        return rows.get(context.jobId()).progressDone();
    }

    private JobId submit(String idempotencyKey) {
        return jobApi.submit(projectId, ScriptedJobHandler.TYPE, idempotencyKey, Map.of("sha", "abc123"))
                .id();
    }

    private RunningJob claim(String workerId) {
        return runner.claimNext(workerId).orElseThrow(() -> new AssertionError("expected a claimable job"));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("start signal never came");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
