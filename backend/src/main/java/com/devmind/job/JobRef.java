package com.devmind.job;

import java.util.Objects;

/**
 * The result of a submit: the job now responsible for the work.
 *
 * @param id the new job, or the existing live job with the same idempotency key
 * @param status its status at submit time; anything but {@link JobStatus#QUEUED} means an existing job was returned
 */
public record JobRef(JobId id, JobStatus status) {

    /** @throws NullPointerException if any component is null */
    public JobRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
    }
}
