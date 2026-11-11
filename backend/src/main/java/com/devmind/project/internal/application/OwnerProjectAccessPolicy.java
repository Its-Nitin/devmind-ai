package com.devmind.project.internal.application;

import com.devmind.identity.UserId;
import com.devmind.project.ProjectAccessPolicy;
import com.devmind.project.ProjectId;
import com.devmind.project.internal.domain.ProjectNotFoundException;
import com.devmind.project.internal.persistence.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** v1 rule: only the owner may touch a project (ADR-0010). One indexed query, no call into identity. */
@Service
class OwnerProjectAccessPolicy implements ProjectAccessPolicy {

    private final ProjectRepository projects;

    OwnerProjectAccessPolicy(ProjectRepository projects) {
        this.projects = projects;
    }

    @Override
    @Transactional(readOnly = true)
    public long requireOwner(UserId user, ProjectId project) {
        return projects.findIdByPublicIdAndOwnerId(project.value(), user.value())
                .orElseThrow(ProjectNotFoundException::new);
    }
}
