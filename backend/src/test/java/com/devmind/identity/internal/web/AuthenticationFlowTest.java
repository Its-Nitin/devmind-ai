package com.devmind.identity.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.devmind.identity.UserId;
import com.devmind.identity.internal.application.UserRegistrationService;
import com.devmind.support.IntegrationTest;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session login, CSRF and 401/403 ProblemDetails (ADR-0010, SEC-7, API-3). Cookies, CSRF round trips and session ID
 * rotation are tested in {@link SessionCookieTest} against the real container: MockMvc's {@code csrf()} swaps the
 * filter's token repository for a session-backed test one, and a mock session has no requested session ID to rotate.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(AuthenticationFlowTest.MdcProbeController.class)
class AuthenticationFlowTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String INVALID_CREDENTIALS = """
            {"status": 401, "code": "invalid-credentials", "detail": "Email or password is incorrect.",
             "type": "https://devmind.dev/problems/invalid-credentials"}
            """;

    private final MockMvcTester mvc;
    private final UserRegistrationService registration;

    private String email = "";
    private UserId userId = new UserId(new UUID(0, 0));

    @Autowired
    AuthenticationFlowTest(MockMvcTester mvc, UserRegistrationService registration) {
        this.mvc = mvc;
        this.registration = registration;
    }

    @BeforeEach
    void registerUser() {
        email = "user-" + UUID.randomUUID() + "@test.local";
        userId = registration.register(email, "Test User", PASSWORD);
    }

    @Test
    void loginStartsSessionAndMeReturnsTheUser() {
        // When
        MvcTestResult login = logIn(email, PASSWORD, new MockHttpSession());

        // Then
        assertThat(login).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                        {"email": "%s", "displayName": "Test User"}
                        """.formatted(email));
        assertThat(login)
                .bodyJson()
                .extractingPath("$.userId")
                .asString()
                .isEqualTo(userId.value().toString());

        MvcTestResult me =
                mvc.get().uri("/api/auth/me").session(sessionOf(login)).exchange();
        assertThat(me).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                        {"email": "%s", "displayName": "Test User"}
                        """.formatted(email));
    }

    @Test
    void emailMatchingIsCaseInsensitive() {
        // When
        MvcTestResult login = logIn(email.toUpperCase(Locale.ROOT), PASSWORD, new MockHttpSession());

        // Then
        assertThat(login).hasStatusOk();
    }

    @Test
    void wrongPasswordAndUnknownEmailFailIdentically() {
        // When
        MvcTestResult wrongPassword = logIn(email, "not-the-password", new MockHttpSession());
        MvcTestResult unknownEmail =
                logIn("nobody-" + UUID.randomUUID() + "@test.local", PASSWORD, new MockHttpSession());

        // Then: same status, code and detail, so the response doesn't reveal whether the account exists
        assertThat(wrongPassword)
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo(INVALID_CREDENTIALS);
        assertThat(unknownEmail).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().isLenientlyEqualTo(INVALID_CREDENTIALS);
    }

    @Test
    void mutatingRequestWithoutCsrfTokenIsForbidden() {
        // When
        MvcTestResult result = mvc.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email, PASSWORD))
                .exchange();

        // Then
        assertThat(result)
                .hasStatus(HttpStatus.FORBIDDEN)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"status": 403, "code": "invalid-csrf-token"}
                        """);
    }

    @Test
    void anonymousApiRequestGetsUnauthorizedProblem() {
        // When
        MvcTestResult result = mvc.get().uri("/api/projects").exchange();

        // Then
        assertThat(result)
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"type": "https://devmind.dev/problems/unauthenticated", "status": 401,
                         "code": "unauthenticated"}
                        """);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.correlationId")
                .asString()
                .isNotBlank();
    }

    @Test
    void meWithoutSessionIsUnauthorized() {
        assertThat(mvc.get().uri("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutEndsTheSession() {
        // Given
        MockHttpSession session = sessionOf(logIn(email, PASSWORD, new MockHttpSession()));

        // When
        MvcTestResult logout =
                mvc.post().uri("/api/auth/logout").with(csrf()).session(session).exchange();

        // Then
        assertThat(logout).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.get().uri("/api/auth/me").session(session)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void signedInRequestsCarryTheUserIdInTheMdc() {
        // Given
        MvcTestResult login = logIn(email, PASSWORD, new MockHttpSession());

        // When
        MvcTestResult probe =
                mvc.get().uri("/test-probe/mdc-user").session(sessionOf(login)).exchange();

        // Then
        assertThat(probe).hasStatusOk().hasBodyTextEqualTo(userId.value().toString());
    }

    private MvcTestResult logIn(String loginEmail, String password, MockHttpSession session) {
        return mvc.post()
                .uri("/api/auth/login")
                .with(csrf())
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(loginEmail, password))
                .exchange();
    }

    private static String credentials(String loginEmail, String password) {
        return """
                {"email": "%s", "password": "%s"}
                """.formatted(loginEmail, password);
    }

    private static MockHttpSession sessionOf(MvcTestResult result) {
        return (MockHttpSession) Objects.requireNonNull(result.getRequest().getSession(false));
    }

    /** Test-only endpoint that echoes the MDC user ID. Nested, so component scanning skips it. */
    @RestController
    static class MdcProbeController {

        @GetMapping("/test-probe/mdc-user")
        @Nullable
        String mdcUser() {
            return MDC.get(UserIdMdcFilter.MDC_KEY);
        }
    }
}
