package com.devmind.identity.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.identity.internal.application.UserRegistrationService;
import com.devmind.support.TestcontainersConfiguration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The real servlet container, doing what a browser or curl does: fetch the XSRF-TOKEN cookie, echo it as a header,
 * log in, reuse the session cookie. It also checks what MockMvc can't show: cookie flags, token rotation and session
 * ID rotation (SEC-7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SessionCookieTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String SESSION_COOKIE = "JSESSIONID";

    private final RestTestClient client;
    private final String email = "user-" + UUID.randomUUID() + "@test.local";

    @Autowired
    SessionCookieTest(RestTestClient client, UserRegistrationService registration) {
        this.client = client;
        registration.register(email, "Cookie User", PASSWORD); // a fresh instance, and user, per test
    }

    @Test
    void csrfThenLoginIssuesHardenedSessionCookieThatAuthenticatesMe() {
        // Given
        String xsrfToken = fetchCsrfToken();

        // When
        EntityExchangeResult<String> login = logIn(xsrfToken, null)
                .expectStatus()
                .isOk()
                .expectCookie()
                .httpOnly(SESSION_COOKIE, true)
                .expectCookie()
                .secure(SESSION_COOKIE, true)
                .expectCookie()
                .sameSite(SESSION_COOKIE, "Lax")
                .expectBody(String.class)
                .returnResult();

        // Then
        assertThat(cookie(login, XSRF_COOKIE))
                .as("login rotates the CSRF token")
                .isNotEqualTo(xsrfToken);
        client.get()
                .uri("/api/auth/me")
                .cookie(SESSION_COOKIE, cookie(login, SESSION_COOKIE))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.email")
                .isEqualTo(email);
    }

    @Test
    void csrfCookieIsReadableByTheSpaAndSameSite() {
        client.get()
                .uri("/api/auth/csrf")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectCookie()
                .httpOnly(XSRF_COOKIE, false)
                .expectCookie()
                .sameSite(XSRF_COOKIE, "Lax");
    }

    @Test
    void loggingInWithAnExistingSessionChangesItsId() {
        // Given: a live session, standing in for one an attacker planted before the victim logs in
        EntityExchangeResult<String> first = logIn(fetchCsrfToken(), null)
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult();
        String sessionId = cookie(first, SESSION_COOKIE);

        // When
        EntityExchangeResult<String> second = logIn(cookie(first, XSRF_COOKIE), sessionId)
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult();

        // Then
        assertThat(cookie(second, SESSION_COOKIE)).isNotEqualTo(sessionId);
    }

    private String fetchCsrfToken() {
        EntityExchangeResult<Void> csrf = client.get()
                .uri("/api/auth/csrf")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .isEmpty();
        return cookie(csrf, XSRF_COOKIE);
    }

    private RestTestClient.ResponseSpec logIn(String xsrfToken, @Nullable String sessionId) {
        return client.post()
                .uri("/api/auth/login")
                .cookies(cookies -> {
                    cookies.add(XSRF_COOKIE, xsrfToken);
                    if (sessionId != null) {
                        cookies.add(SESSION_COOKIE, sessionId);
                    }
                })
                .header("X-XSRF-TOKEN", xsrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD))
                .exchange();
    }

    /**
     * The value of the last {@code Set-Cookie} for {@code name}, as a browser keeps it: on login the token is first
     * cleared, then reissued.
     */
    private static String cookie(EntityExchangeResult<?> result, String name) {
        List<ResponseCookie> cookies = Objects.requireNonNull(
                result.getResponseCookies().get(name), () -> "no " + name + " cookie in the response");
        return cookies.getLast().getValue();
    }
}
