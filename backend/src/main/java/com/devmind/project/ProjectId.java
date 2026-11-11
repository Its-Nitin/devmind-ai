package com.devmind.project;

import java.util.Objects;
import java.util.UUID;

/**
 * A project's public identifier: the {@code project.public_id} UUID used in URLs (JAVA-4, API-5).
 *
 * @param value the UUID, never null
 */
public record ProjectId(UUID value) {

    /** @throws NullPointerException if {@code value} is null */
    public ProjectId {
        Objects.requireNonNull(value, "value");
    }
}
