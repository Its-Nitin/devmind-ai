package com.devmind.project.internal.web;

import com.devmind.project.ProjectSummary;
import com.devmind.project.SourceType;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A project in API responses. {@code projectId} is the public UUID (API-5); timestamps are ISO-8601 UTC (API-9). */
record ProjectResponse(
        UUID projectId,
        String name,
        SourceType sourceType,
        String sourceLocation,
        @Nullable String defaultBranch,
        Instant createdAt) {

    static ProjectResponse of(ProjectSummary project) {
        return new ProjectResponse(
                project.id().value(),
                project.name(),
                project.sourceType(),
                project.sourceLocation(),
                project.defaultBranch(),
                project.createdAt());
    }
}
