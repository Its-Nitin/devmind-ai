package com.devmind.ingestion;

import com.devmind.project.ProjectSummary;
import com.devmind.project.SourceType;
import java.util.Objects;

/**
 * Where a project's code is read from.
 *
 * @param type how {@code location} is read
 * @param location an absolute directory for {@link SourceType#LOCAL}, an HTTPS URL for {@link SourceType#GIT_PUBLIC};
 *     untrusted until {@link SourceSnapshotProvider#open} validates it
 */
public record ProjectSource(SourceType type, String location) {

    /** @throws NullPointerException if an argument is null */
    public ProjectSource {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(location, "location");
    }

    /** Returns the source of {@code project}. */
    public static ProjectSource of(ProjectSummary project) {
        return new ProjectSource(project.sourceType(), project.sourceLocation());
    }
}
