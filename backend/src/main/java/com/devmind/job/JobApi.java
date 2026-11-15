package com.devmind.job;

import com.devmind.identity.UserId;
import java.util.Map;

/** Submitting and reading background jobs (ADR-0009). */
public interface JobApi {

    /**
     * Queues a job, or returns the existing one: while a job with the same {@code (projectId, type, idempotencyKey)}
     * is not {@link JobStatus#FAILED}, submitting again returns it instead of creating a new one.
     *
     * <p>Doesn't check access: the caller has already authorized the project with {@code ProjectAccessPolicy} (SEC-1)
     * and passes the internal ID it got back.
     *
     * @param projectId internal project ID, as returned by {@code ProjectAccessPolicy.requireOwner}
     * @param idempotencyKey 1 to 200 characters, e.g. commit SHA + index config hash
     * @param payload JSON-serializable input for the handler, no null values; never logged (OBS-3)
     * @throws IllegalArgumentException if {@code idempotencyKey} is blank or too long
     */
    JobRef submit(long projectId, JobType type, String idempotencyKey, Map<String, Object> payload);

    /**
     * Returns a job of a project that {@code user} owns.
     *
     * @throws com.devmind.shared.NotFoundException ({@code job-not-found}) if the job doesn't exist or belongs to
     *     someone else's project; both look the same (API-8)
     */
    JobView get(UserId user, JobId id);
}
