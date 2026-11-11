package com.devmind.project.internal.domain;

import com.devmind.shared.NotFoundException;
import java.io.Serial;

/** The project doesn't exist or the caller doesn't own it; deliberately indistinguishable (API-8). */
public class ProjectNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ProjectNotFoundException() {
        super("project-not-found", "Project not found.");
    }
}
