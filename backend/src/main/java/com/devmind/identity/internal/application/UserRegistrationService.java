package com.devmind.identity.internal.application;

import com.devmind.identity.UserId;
import com.devmind.identity.internal.persistence.AppUser;
import com.devmind.identity.internal.persistence.AppUserRepository;
import com.devmind.shared.ConflictException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates user accounts. v1 has no sign-up endpoint: users come from the {@code local} seed or from tests. Passwords
 * are hashed with the delegating encoder, which defaults to bcrypt (SEC-7).
 */
@Service
public class UserRegistrationService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserRegistrationService(AppUserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Registers a user.
     *
     * @return the new user's ID
     * @throws ConflictException ({@code user-exists}) if the email is already taken, compared case-insensitively
     */
    @Transactional
    public UserId register(String email, String displayName, String rawPassword) {
        if (users.existsByEmail(email)) {
            throw new ConflictException("user-exists", "A user with this email already exists.");
        }
        AppUser user =
                users.save(new AppUser(UUID.randomUUID(), email, displayName, encode(rawPassword), clock.instant()));
        return new UserId(user.publicId());
    }

    /** Returns whether a user with this email exists, compared case-insensitively. */
    @Transactional(readOnly = true)
    public boolean isRegistered(String email) {
        return users.existsByEmail(email);
    }

    private String encode(String rawPassword) {
        String hash = passwordEncoder.encode(rawPassword);
        if (hash == null) {
            throw new IllegalStateException("Password encoder returned no hash");
        }
        return hash;
    }
}
