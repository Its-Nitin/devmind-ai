package com.devmind.project;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A project as other modules see it.
 *
 * @param sourceLocation the path or URL as entered; only syntax-checked until ingestion validates it (SEC-2/3)
 * @param defaultBranch null until the first ingestion resolves it
 */
public record ProjectSummary(
        ProjectId id,
        String name,
        SourceType sourceType,
        String sourceLocation,
        @Nullable String defaultBranch,
        Instant createdAt) {}
