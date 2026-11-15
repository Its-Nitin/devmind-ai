package com.devmind.job.internal.persistence;

import java.util.Objects;

/**
 * A worker's claim on one attempt of a job; every write by that worker is guarded by it. The attempt number is a
 * fencing token: once the lease expires and the job is claimed again, the job's attempt counter moves on and the old
 * attempt's writes match no row, even if the same worker made both claims.
 *
 * @param jobId internal job ID
 * @param workerId the {@code locked_by} value of the claim
 * @param attempt the attempt counter value the claim set, starting at 1
 */
public record Lease(long jobId, String workerId, int attempt) {

    public Lease {
        Objects.requireNonNull(workerId, "workerId");
    }
}
