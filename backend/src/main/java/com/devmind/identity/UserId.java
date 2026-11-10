package com.devmind.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * A user's public identifier: the {@code app_user.public_id} UUID (JAVA-4, API-5). Internal numeric IDs never leave
 * the identity module.
 *
 * @param value the UUID, never null
 */
public record UserId(UUID value) {

    /** @throws NullPointerException if {@code value} is null */
    public UserId {
        Objects.requireNonNull(value, "value");
    }
}
