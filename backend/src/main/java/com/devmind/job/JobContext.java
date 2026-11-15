package com.devmind.job;

import java.util.Map;

/**
 * What a {@link JobHandler} gets for one attempt. Use it from the handler's own thread.
 *
 * <p>The worker holds a time-limited lease on the job. Long handlers call {@link #heartbeat()} between phases or
 * every N files; when the lease expires, another worker may claim the job and this attempt's writes to the job are
 * discarded.
 */
public interface JobContext {

    /** Returns the job's public ID, never null. */
    JobId jobId();

    /** Returns the internal ID of the job's project, for server-side joins only (API-5). */
    long projectId();

    /** Returns this attempt's number, starting at 1. */
    int attempt();

    /** Returns the payload given at submit, unmodifiable and never null. */
    Map<String, Object> payload();

    /**
     * Reports progress. Writes are throttled (at most one per {@code devmind.job.progress-interval}), except that a
     * report with {@code done >= total} is always written.
     *
     * @param phase handler-defined, e.g. {@code parsing}; shown to the user, so it must not contain code or paths
     * @throws IllegalArgumentException if {@code done} or {@code total} is negative
     */
    void progress(String phase, long done, long total);

    /**
     * Extends the lease by {@code devmind.job.lease-duration} from now.
     *
     * @return false if the lease is already lost (it expired and another attempt took over); the handler should then
     *     stop, because its outcome will be discarded
     */
    boolean heartbeat();
}
