package com.devmind.job;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A job as its owner sees it.
 *
 * @param attempts attempts started so far, including the current one
 * @param errorCode machine-readable code of the last failure (ERR-5), or null if none; kept while a retry is queued
 */
public record JobView(
        JobId id,
        JobType type,
        JobStatus status,
        JobProgress progress,
        int attempts,
        @Nullable String errorCode,
        Instant createdAt,
        Instant updatedAt) {}
