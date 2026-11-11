package com.devmind.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.devmind.identity.UserId;
import com.devmind.identity.internal.application.UserRegistrationService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Creates users and signs them in through the real login endpoint, so tests use genuine sessions instead of mock
 * principals. Each call creates a new user with a unique email, because the test database isn't cleaned between tests.
 */
public final class TestUsers {

    public static final String PASSWORD = "correct-horse-battery";

    private final MockMvcTester mvc;
    private final UserRegistrationService registration;

    public TestUsers(MockMvcTester mvc, UserRegistrationService registration) {
        this.mvc = mvc;
        this.registration = registration;
    }

    /** A signed-in user; send {@link #session()} with each request. */
    public record SignedInUser(UserId id, String email, MockHttpSession session) {}

    public SignedInUser signIn() {
        String email = "user-" + UUID.randomUUID() + "@test.local";
        UserId id = registration.register(email, "Test User", PASSWORD);
        MvcTestResult login = mvc.post()
                .uri("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD))
                .exchange();
        assertThat(login).hasStatusOk();
        MockHttpSession session =
                (MockHttpSession) Objects.requireNonNull(login.getRequest().getSession(false));
        return new SignedInUser(id, email, session);
    }
}
