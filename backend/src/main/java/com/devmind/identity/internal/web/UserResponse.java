package com.devmind.identity.internal.web;

import com.devmind.identity.internal.application.AuthenticatedUser;
import java.util.UUID;

/** The signed-in user. {@code userId} is the public UUID (API-5). */
record UserResponse(UUID userId, String email, String displayName) {

    static UserResponse of(AuthenticatedUser user) {
        return new UserResponse(user.userId().value(), user.email(), user.displayName());
    }
}
