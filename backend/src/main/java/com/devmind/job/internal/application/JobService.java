package com.devmind.job.internal.application;

import com.devmind.identity.UserId;
import com.devmind.job.JobApi;
import com.devmind.job.JobId;
import com.devmind.job.JobRef;
import com.devmind.job.JobType;
import com.devmind.job.JobView;
import com.devmind.job.internal.domain.JobNotFoundException;
import com.devmind.job.internal.persistence.JobQueries;
import com.devmind.job.internal.persistence.JobRecord;
import com.devmind.project.ProjectAccessPolicy;
import com.devmind.shared.NotFoundException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Submitting and reading jobs. Reads resolve the job's project and check ownership first (SEC-1, ADR-0010). */
@Service
public class JobService implements JobApi {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    private final JobQueries jobs;
    private final ProjectAccessPolicy accessPolicy;
    private final JobProperties properties;
    private final Clock clock;

    JobService(JobQueries jobs, ProjectAccessPolicy accessPolicy, JobProperties properties, Clock clock) {
        this.jobs = jobs;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    /*
     * Insert-or-nothing, then read the live row: the partial unique index decides between concurrent submits. Under
     * READ COMMITTED the SELECT sees a row a concurrent submit committed, because ON CONFLICT waited for it.
     */
    @Override
    @Transactional
    public JobRef submit(long projectId, JobType type, String idempotencyKey, Map<String, Object> payload) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "idempotencyKey must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        jobs.insertIfAbsent(
                new JobId(UUID.randomUUID()),
                projectId,
                type,
                idempotencyKey,
                Map.copyOf(payload),
                properties.maxAttempts(),
                clock.instant());
        JobRecord job = jobs.findLive(projectId, type, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("live job missing right after insert-if-absent"));
        return new JobRef(job.publicId(), job.status());
    }

    @Override
    @Transactional(readOnly = true)
    public JobView get(UserId user, JobId id) {
        JobRecord job = jobs.findByPublicId(id).orElseThrow(JobNotFoundException::new);
        requireOwnerOfProject(user, job);
        return new JobView(
                job.publicId(),
                job.type(),
                job.status(),
                job.progress(),
                job.attempts(),
                job.errorCode(),
                job.createdAt(),
                job.updatedAt());
    }

    /** Someone else's job answers exactly like a missing one, so job IDs can't be probed (API-8). */
    @SuppressWarnings("UnusedException") // the cause only says project-not-found, which is what must not leak
    private void requireOwnerOfProject(UserId user, JobRecord job) {
        try {
            accessPolicy.requireOwner(user, job.projectId());
        } catch (NotFoundException notOwner) {
            throw new JobNotFoundException();
        }
    }
}
