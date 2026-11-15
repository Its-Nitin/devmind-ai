package com.devmind.job;

/**
 * SPI: runs the jobs of one {@link JobType}. Implemented by the module that owns the work (indexing in M2, impact in
 * M5) as a Spring bean; the worker only claims jobs whose type has a handler.
 *
 * <p>Handlers must be re-runnable: an attempt can be repeated after a crash or an expired lease, so writes are
 * idempotent (ADR-0009). A handler runs outside any transaction and opens its own (SPRING-2).
 */
public interface JobHandler {

    /** Returns the job type this handler runs, never null. Each type has at most one handler. */
    JobType type();

    /**
     * Runs one attempt of a job.
     *
     * <p>A thrown exception counts as {@link JobOutcome.RetryableFailure} with code {@code unexpected-error}; its
     * message isn't stored, so it may contain detail for the logs.
     *
     * @return what happened, never null
     */
    JobOutcome handle(JobContext context);
}
