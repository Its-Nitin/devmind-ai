package com.devmind.identity.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.devmind.identity.UserId;
import com.devmind.identity.internal.persistence.AppUser;
import com.devmind.identity.internal.persistence.AppUserRepository;
import com.devmind.shared.ConflictException;
import com.devmind.support.IntegrationTest;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

@IntegrationTest
class UserRegistrationServiceTest {

    private final UserRegistrationService registration;
    private final AppUserRepository users;

    @Autowired
    UserRegistrationServiceTest(UserRegistrationService registration, AppUserRepository users) {
        this.registration = registration;
        this.users = users;
    }

    @Test
    void storesBcryptHashNeverThePlainPassword() {
        // Given
        String email = uniqueEmail();

        // When
        UserId id = registration.register(email, "Hash Check", "plain-password-123");

        // Then (SEC-7)
        AppUser stored = users.findByEmail(email).orElseThrow();
        assertThat(stored.publicId()).isEqualTo(id.value());
        assertThat(stored.passwordHash()).startsWith("{bcrypt}$2").doesNotContain("plain-password-123");
    }

    @Test
    void rejectsEmailThatDiffersOnlyInCase() {
        // Given
        String email = uniqueEmail();
        registration.register(email, "First", "plain-password-123");

        // When / Then
        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> registration.register(email.toUpperCase(Locale.ROOT), "Second", "other-password-1"))
                .satisfies(ex -> assertThat(ex.code()).isEqualTo("user-exists"));
    }

    @Test
    void seedingTwiceCreatesTheDemoUserOnce() {
        // Given
        String email = uniqueEmail();
        DemoUserSeeder seeder =
                new DemoUserSeeder(new DemoUserProperties(email, "Demo", "seed-password-123"), registration);

        // When
        seeder.run(new DefaultApplicationArguments());
        seeder.run(new DefaultApplicationArguments());

        // Then
        assertThat(registration.isRegistered(email)).isTrue();
        assertThat(users.findAll())
                .filteredOn(user -> user.email().equals(email))
                .hasSize(1);
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.local";
    }
}
