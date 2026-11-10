package com.devmind.identity.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.identity.internal.application.UserRegistrationService;
import com.devmind.support.TestcontainersConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The real servlet container over loopback HTTP, doing what a browser or curl does: fetch the XSRF-TOKEN cookie, echo
 * it as a header, log in, reuse the session cookie. It also checks what MockMvc can't show: cookie flags, token
 * rotation and session ID rotation (SEC-7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SessionCookieTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String SESSION_COOKIE = "JSESSIONID";

    private final HttpClient http = HttpClient.newHttpClient();
    private final UserRegistrationService registration;
    private final int port;

    private String email = "";

    @Autowired
    SessionCookieTest(UserRegistrationService registration, @LocalServerPort int port) {
        this.registration = registration;
        this.port = port;
    }

    @BeforeEach
    void registerUser() {
        email = "user-" + UUID.randomUUID() + "@test.local";
        registration.register(email, "Cookie User", PASSWORD);
    }

    @Test
    void csrfThenLoginIssuesHardenedSessionCookieThatAuthenticatesMe() throws IOException, InterruptedException {
        // Given
        HttpResponse<String> csrf =
                send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET());
        String xsrfCookie = setCookie(csrf, XSRF_COOKIE);
        assertThat(xsrfCookie).doesNotContainIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");

        // When
        HttpResponse<String> login = logIn(cookieValue(xsrfCookie), null);

        // Then
        assertThat(login.statusCode()).isEqualTo(200);
        String sessionCookie = setCookie(login, SESSION_COOKIE);
        assertThat(sessionCookie)
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("Secure")
                .containsIgnoringCase("SameSite=Lax");
        assertThat(cookieValue(setCookie(login, XSRF_COOKIE)))
                .as("login rotates the CSRF token")
                .isNotBlank()
                .isNotEqualTo(cookieValue(xsrfCookie));

        HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/auth/me"))
                .header("Cookie", SESSION_COOKIE + "=" + cookieValue(sessionCookie))
                .GET());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains(email);
    }

    @Test
    void loggingInWithAnExistingSessionChangesItsId() throws IOException, InterruptedException {
        // Given: a live session, standing in for one an attacker planted before the victim logs in
        String firstToken = cookieValue(
                setCookie(send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET()), XSRF_COOKIE));
        HttpResponse<String> first = logIn(firstToken, null);
        String sessionId = cookieValue(setCookie(first, SESSION_COOKIE));
        String rotatedToken = cookieValue(setCookie(first, XSRF_COOKIE));

        // When
        HttpResponse<String> second = logIn(rotatedToken, sessionId);

        // Then
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(cookieValue(setCookie(second, SESSION_COOKIE))).isNotEqualTo(sessionId);
    }

    private HttpResponse<String> logIn(String xsrfToken, @Nullable String sessionId)
            throws IOException, InterruptedException {
        String cookies =
                XSRF_COOKIE + "=" + xsrfToken + (sessionId == null ? "" : "; " + SESSION_COOKIE + "=" + sessionId);
        return send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("Cookie", cookies)
                .header("X-XSRF-TOKEN", xsrfToken)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD))));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** The last {@code Set-Cookie} for {@code name}, as a browser would keep it. */
    private static String setCookie(HttpResponse<?> response, String name) {
        List<String> cookies = response.headers().allValues("Set-Cookie");
        return cookies.stream()
                .filter(cookie -> cookie.startsWith(name + "="))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No " + name + " cookie in " + cookies));
    }

    private static String cookieValue(String setCookie) {
        int end = setCookie.indexOf(';');
        return setCookie.substring(setCookie.indexOf('=') + 1, end < 0 ? setCookie.length() : end);
    }
}
