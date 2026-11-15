package com.devmind.job.internal.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Polls for due jobs and runs them on virtual threads, at most {@code devmind.job.max-concurrent} at a time (ADR-0009,
 * JAVA-11). One poller thread claims a job only when a slot is free, so a claimed job never waits for a slot while
 * its lease runs down. Stopping waits up to {@code devmind.job.shutdown-timeout} for running handlers; anything still
 * running is reclaimed by another worker once its lease expires.
 */
@Component
class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobRunner runner;
    private final JobProperties properties;
    /** The {@code locked_by} value of this instance's claims. A restart reuses it safely: leases also fence by attempt. */
    private final String workerId = "worker-" + UUID.randomUUID();

    private volatile boolean running;
    private @Nullable Thread poller;
    private @Nullable ExecutorService handlers;

    JobWorker(JobRunner runner, JobProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.workerEnabled();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Semaphore slots = new Semaphore(properties.maxConcurrent());
        running = true;
        handlers = executor;
        poller = Thread.ofVirtual().name("job-poller").start(() -> pollUntilStopped(executor, slots));
        log.info("Job worker {} started: max-concurrent={}", workerId, properties.maxConcurrent());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (poller != null) {
            poller.interrupt();
        }
        if (handlers != null) {
            shutDown(handlers);
        }
        poller = null;
        handlers = null;
        log.info("Job worker {} stopped", workerId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollUntilStopped(ExecutorService executor, Semaphore slots) {
        while (running) {
            try {
                slots.acquire();
                if (!dispatchNext(executor, slots)) {
                    slots.release();
                    Thread.sleep(properties.pollInterval());
                }
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Claims one job and hands it to a handler thread that owns the slot. Returns false if nothing was claimed, in
     * which case the caller still holds the slot.
     */
    private boolean dispatchNext(ExecutorService executor, Semaphore slots) {
        Optional<RunningJob> claimed;
        try {
            runner.failAbandoned();
            claimed = runner.claimNext(workerId);
        } catch (RuntimeException ex) {
            // JAVA-8 boundary: the database may be briefly unavailable; try again after the poll interval.
            log.warn("Job poll failed; retrying after {}", properties.pollInterval(), ex);
            return false;
        }
        if (claimed.isEmpty()) {
            return false;
        }
        RunningJob attempt = claimed.orElseThrow();
        try {
            executor.execute(() -> {
                try {
                    runner.run(attempt);
                } finally {
                    slots.release();
                }
            });
        } catch (RejectedExecutionException stopping) {
            // Shutting down: the claimed job is reclaimed once its lease expires.
            log.info("Job worker stopping; claimed job left for its lease to expire");
            return false;
        }
        return true;
    }

    private void shutDown(ExecutorService executor) {
        executor.shutdown();
        Duration timeout = properties.shutdownTimeout();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn(
                        "Job handlers still running after {}; their jobs will be reclaimed when the leases expire",
                        timeout);
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
