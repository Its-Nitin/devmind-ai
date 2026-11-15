package com.devmind.job.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.devmind.job.JobApi;
import com.devmind.job.JobId;
import com.devmind.job.JobOutcome;
import com.devmind.support.IntegrationTest;
import com.devmind.support.MutableClock;
import com.devmind.support.TestProjects;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The polling worker end to end: start, bounded concurrency, drain, graceful stop (ADR-0009, JAVA-11). */
@IntegrationTest
@Import(JobTestConfiguration.class)
class JobWorkerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final JobWorker worker;
    private final JobApi jobApi;
    private final ScriptedJobHandler handler;
    private final MutableClock clock;
    private final JobProperties properties;
    private final JobRows rows;
    private final long projectId;

    @Autowired
    JobWorkerTest(
            JobWorker worker,
            JobApi jobApi,
            ScriptedJobHandler handler,
            MutableClock clock,
            JobProperties properties,
            JdbcClient jdbc) {
        this.worker = worker;
        this.jobApi = jobApi;
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

    @AfterEach
    void stopWorker() {
        worker.stop();
    }

    @Test
    void isOffUntilStartedInTests() {
        assertThat(worker.isAutoStartup()).isFalse();
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    void startedWorkerRunsQueuedJobsAndStops() {
        // Given
        List<JobId> jobs = submit(3);

        // When
        worker.start();

        // Then
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(jobs)
                        .allSatisfy(job -> assertThat(rows.get(job).status()).isEqualTo("SUCCEEDED")));
        worker.stop();
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    void runsAtMostMaxConcurrentHandlersAtOnce() {
        // Given: handlers block until released
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        handler.willDo(context -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (!release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    return JobOutcome.permanent("test-timeout", "Never released.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return JobOutcome.succeeded();
        });
        int max = properties.maxConcurrent();
        List<JobId> jobs = submit(max + 3);

        // When
        worker.start();
        await().atMost(TIMEOUT).until(() -> active.get() == max);

        // Then: a job is claimed only when a slot is free, so the rest are still queued, not RUNNING
        assertThat(jobs.stream().filter(job -> rows.get(job).status().equals("RUNNING")))
                .hasSize(max);
        release.countDown();
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(jobs)
                        .allSatisfy(job -> assertThat(rows.get(job).status()).isEqualTo("SUCCEEDED")));
        assertThat(peak.get()).isEqualTo(max);
    }

    private List<JobId> submit(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> jobApi.submit(projectId, ScriptedJobHandler.TYPE, "k" + i, Map.of())
                        .id())
                .toList();
    }
}
