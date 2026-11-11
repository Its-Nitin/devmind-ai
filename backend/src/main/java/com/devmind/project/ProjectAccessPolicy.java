package com.devmind.project;

import com.devmind.identity.UserId;

/**
 * The single authorization check for project-scoped data (ADR-0010, SEC-1). Every application service method that
 * takes a project-scoped identifier calls it first.
 */
public interface ProjectAccessPolicy {

    /**
     * Returns the internal ID of {@code project} if {@code user} owns it. The ID is for server-side joins only and never
     * leaves the server (API-5).
     *
     * @throws com.devmind.shared.NotFoundException ({@code project-not-found}) if the project doesn't exist or belongs
     *     to someone else; both look the same, so existence isn't revealed (API-8)
     */
    long requireOwner(UserId user, ProjectId project);
}
