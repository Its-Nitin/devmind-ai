package com.devmind.job.internal.web;

import com.devmind.job.JobProgress;
import com.devmind.job.JobStatus;
import com.devmind.job.JobType;
import com.devmind.job.JobView;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A job in API responses. {@code jobId} is the public UUID (API-5); timestamps are ISO-8601 UTC (API-9).
 *
 * @param errorCode code of the last failure; set on FAILED, and on QUEUED while a retry waits
 */
record JobResponse(
        UUID jobId,
        JobType type,
        JobStatus status,
        JobProgressResponse progress,
        int attempts,
        @Nullable String errorCode,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * The handler's last progress report.
     *
     * @param phase null until the first report
     * @param total 0 when unknown
     */
    record JobProgressResponse(@Nullable String phase, long done, long total) {

        static JobProgressResponse of(JobProgress progress) {
            return new JobProgressResponse(progress.phase(), progress.done(), progress.total());
        }
    }

    static JobResponse of(JobView job) {
        return new JobResponse(
                job.id().value(),
                job.type(),
                job.status(),
                JobProgressResponse.of(job.progress()),
                job.attempts(),
                job.errorCode(),
                job.createdAt(),
                job.updatedAt());
    }
}
