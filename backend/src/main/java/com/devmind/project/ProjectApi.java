package com.devmind.project;

import com.devmind.identity.UserId;

/** Read access to projects for other modules (e.g. ingestion, from M2). */
public interface ProjectApi {

    /**
     * Returns the project, never null.
     *
     * @throws com.devmind.shared.NotFoundException ({@code project-not-found}) if it doesn't exist or {@code user}
     *     doesn't own it (API-8)
     */
    ProjectSummary get(UserId user, ProjectId id);
}
