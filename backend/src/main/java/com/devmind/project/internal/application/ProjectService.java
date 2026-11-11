package com.devmind.project.internal.application;

import com.devmind.identity.UserId;
import com.devmind.project.ProjectAccessPolicy;
import com.devmind.project.ProjectApi;
import com.devmind.project.ProjectId;
import com.devmind.project.ProjectSummary;
import com.devmind.project.internal.domain.ProjectNameTakenException;
import com.devmind.project.internal.domain.ProjectNotFoundException;
import com.devmind.project.internal.persistence.Project;
import com.devmind.project.internal.persistence.ProjectRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project use cases. Every method taking a {@link ProjectId} calls {@link ProjectAccessPolicy} first (SEC-1), so a
 * non-owner gets the same 404 as for a missing project (API-8).
 */
@Service
public class ProjectService implements ProjectApi {

    private final ProjectRepository projects;
    private final ProjectAccessPolicy accessPolicy;
    private final Clock clock;

    public ProjectService(ProjectRepository projects, ProjectAccessPolicy accessPolicy, Clock clock) {
        this.projects = projects;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    /**
     * Creates a project owned by {@code owner}.
     *
     * @throws ProjectNameTakenException if the owner already has a project with this name
     */
    @Transactional
    public ProjectSummary create(UserId owner, NewProject project) {
        if (projects.existsByOwnerIdAndName(owner.value(), project.name())) {
            throw new ProjectNameTakenException();
        }
        Project entity = new Project(
                UUID.randomUUID(),
                owner.value(),
                project.name(),
                project.sourceType(),
                project.sourceLocation(),
                clock.instant());
        try {
            return toSummary(projects.saveAndFlush(entity));
        } catch (DataIntegrityViolationException ex) {
            // A concurrent create won the race for project_owner_name_uk after the check above.
            throw new ProjectNameTakenException();
        }
    }

    /** Returns one page of the owner's projects; the caller has already whitelisted the sort (DB-5, API-6). */
    @Transactional(readOnly = true)
    public Page<ProjectSummary> listOwn(UserId owner, Pageable pageable) {
        return projects.findByOwnerId(owner.value(), pageable).map(ProjectService::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectSummary get(UserId user, ProjectId id) {
        long internalId = accessPolicy.requireOwner(user, id);
        return projects.findById(internalId).map(ProjectService::toSummary).orElseThrow(ProjectNotFoundException::new);
    }

    /**
     * Deletes the project; the database cascades to everything it owns (tested in M6).
     *
     * @throws ProjectNotFoundException if it doesn't exist or {@code user} doesn't own it
     */
    @Transactional
    public void delete(UserId user, ProjectId id) {
        projects.deleteById(accessPolicy.requireOwner(user, id));
    }

    private static ProjectSummary toSummary(Project project) {
        return new ProjectSummary(
                new ProjectId(project.publicId()),
                project.name(),
                project.sourceType(),
                project.sourceLocation(),
                project.defaultBranch(),
                project.createdAt());
    }
}
