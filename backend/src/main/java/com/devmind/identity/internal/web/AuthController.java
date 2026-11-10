package com.devmind.identity.internal.web;

import com.devmind.identity.internal.application.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session login for the SPA (ADR-0010). Logout is Spring Security's logout filter on {@code POST /api/auth/logout}
 * (204), not a handler here.
 */
@RestController
class AuthController {

    private final SessionLogin sessionLogin;

    AuthController(SessionLogin sessionLogin) {
        this.sessionLogin = sessionLogin;
    }

    /** Starts a session. The response also carries a fresh XSRF-TOKEN cookie. */
    @PostMapping(SecurityConfiguration.LOGIN_PATH)
    UserResponse logIn(
            @Valid @RequestBody LoginRequest login, HttpServletRequest request, HttpServletResponse response) {
        return UserResponse.of(sessionLogin.logIn(login.email(), login.password(), request, response));
    }

    @GetMapping("/api/auth/me")
    UserResponse me(@AuthenticationPrincipal @Nullable AuthenticatedUser user) {
        if (user == null) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated DevMind user");
        }
        return UserResponse.of(user);
    }

    /** Issues the XSRF-TOKEN cookie. The SPA calls it once on start-up and after logout. */
    @GetMapping(SecurityConfiguration.CSRF_PATH)
    ResponseEntity<Void> csrf(CsrfToken token) {
        token.getToken(); // the token is deferred; reading it writes the cookie
        return ResponseEntity.noContent().build();
    }
}
