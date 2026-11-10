package com.devmind.identity.internal.application;

import com.devmind.identity.UserId;
import com.devmind.identity.internal.persistence.AppUser;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The security principal kept in the HTTP session. It's serialized with the session, so it holds plain values, and
 * the password hash is erased after authentication.
 */
public final class AuthenticatedUser implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;
    private final String email;
    private final String displayName;
    private @Nullable String passwordHash;

    AuthenticatedUser(UUID userId, String email, String displayName, @Nullable String passwordHash) {
        this.userId = userId;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    static AuthenticatedUser from(AppUser user) {
        return new AuthenticatedUser(user.publicId(), user.email(), user.displayName(), user.passwordHash());
    }

    public UserId userId() {
        return new UserId(userId);
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    /** No roles in v1: every user is equal, and access to data is decided per project (SEC-1). */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public @Nullable String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
