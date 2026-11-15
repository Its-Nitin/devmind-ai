package com.devmind.job;

import java.util.Objects;
import java.util.UUID;

/**
 * A job's public identifier: the {@code job.public_id} UUID used in URLs (JAVA-4, API-5).
 *
 * @param value the UUID, never null
 */
public record JobId(UUID value) {

    /** @throws NullPointerException if {@code value} is null */
    public JobId {
        Objects.requireNonNull(value, "value");
    }
}
