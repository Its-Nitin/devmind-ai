package com.devmind.job.internal.application;

import com.devmind.job.JobContext;
import com.devmind.job.JobId;
import com.devmind.job.JobProgress;
import com.devmind.job.internal.persistence.JobQueries;
import com.devmind.job.internal.persistence.JobRecord;
import com.devmind.job.internal.persistence.Lease;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One claimed attempt of a job, handed to its handler. Every write is guarded by the attempt's {@link Lease}. */
final class RunningJob implements JobContext {

    private final JobRecord job;
    private final Lease lease;
    private final JobQueries jobs;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration progressInterval;

    private @Nullable Instant lastProgressWrite;

    RunningJob(JobRecord job, Lease lease, JobQueries jobs, Clock clock, JobProperties properties) {
        this.job = job;
        this.lease = lease;
        this.jobs = jobs;
        this.clock = clock;
        this.leaseDuration = properties.leaseDuration();
        this.progressInterval = properties.progressInterval();
    }

    Lease lease() {
        return lease;
    }

    JobRecord job() {
        return job;
    }

    @Override
    public JobId jobId() {
        return job.publicId();
    }

    @Override
    public long projectId() {
        return job.projectId();
    }

    @Override
    public int attempt() {
        return lease.attempt();
    }

    @Override
    public Map<String, Object> payload() {
        return job.payload();
    }

    @Override
    public void progress(String phase, long done, long total) {
        JobProgress progress = new JobProgress(phase, done, total);
        Instant now = clock.instant();
        boolean finished = done >= total;
        boolean due = lastProgressWrite == null || !now.isBefore(lastProgressWrite.plus(progressInterval));
        if (finished || due) {
            jobs.saveProgress(lease, progress, now);
            lastProgressWrite = now;
        }
    }

    @Override
    public boolean heartbeat() {
        Instant now = clock.instant();
        return jobs.extendLease(lease, now.plus(leaseDuration), now);
    }
}
