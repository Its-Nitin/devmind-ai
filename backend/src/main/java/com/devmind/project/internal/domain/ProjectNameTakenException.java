package com.devmind.project.internal.domain;

import com.devmind.shared.ConflictException;
import java.io.Serial;

/** The owner already has a project with this name ({@code project_owner_name_uk}). */
public class ProjectNameTakenException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ProjectNameTakenException() {
        super("project-name-taken", "You already have a project with this name.");
    }
}
