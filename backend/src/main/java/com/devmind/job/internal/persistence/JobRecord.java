package com.devmind.job.internal.persistence;

import com.devmind.job.JobId;
import com.devmind.job.JobProgress;
import com.devmind.job.JobStatus;
import com.devmind.job.JobType;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A row of {@code job}. Never leaves the job module (API-1). */
public record JobRecord(
        long id,
        JobId publicId,
        long projectId,
        JobType type,
        JobStatus status,
        Map<String, Object> payload,
        int attempts,
        int maxAttempts,
        JobProgress progress,
        @Nullable String errorCode,
        Instant createdAt,
        Instant updatedAt) {

    public JobRecord {
        payload = Map.copyOf(payload);
    }
}
