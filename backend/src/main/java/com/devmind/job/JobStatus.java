package com.devmind.job;

/** Lifecycle of a job. Matches the {@code job.status} CHECK constraint (DB-3). */
public enum JobStatus {
    /** Waiting until {@code run_after}; also the state between retry attempts. */
    QUEUED,
    /** Claimed by a worker that holds an unexpired lease. */
    RUNNING,
    /** The handler finished successfully. Terminal. */
    SUCCEEDED,
    /** Permanently failed, or out of attempts. Terminal; the same idempotency key may be submitted again. */
    FAILED,
    /** Cancelled before finishing. Terminal. Reserved: nothing cancels jobs yet. */
    CANCELLED
}
