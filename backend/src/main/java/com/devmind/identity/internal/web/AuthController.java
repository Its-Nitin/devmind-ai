package com.devmind.identity.internal.web;

import com.devmind.identity.internal.application.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session login for the SPA (ADR-0010). Logout is Spring Security's logout filter on {@code POST /api/auth/logout}
 * (204), not a handler here; {@link SecurityApiDocumentation} adds it to the OpenAPI spec. Method names are the
 * spec's operation IDs.
 */
@RestController
@Tag(name = AuthController.TAG)
class AuthController {

    static final String TAG = "Auth";

    private final SessionLogin sessionLogin;

    AuthController(SessionLogin sessionLogin) {
        this.sessionLogin = sessionLogin;
    }

    @PostMapping(SecurityConfiguration.LOGIN_PATH)
    @Operation(
            summary = "Start a session",
            description = "Sets the session cookie and rotates the XSRF-TOKEN cookie. Needs a CSRF token from GET "
                    + SecurityConfiguration.CSRF_PATH + " first.")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "401", description = "Email or password is incorrect (code: invalid-credentials)")
    UserResponse logIn(
            @Valid @RequestBody LoginRequest login, HttpServletRequest request, HttpServletResponse response) {
        return UserResponse.of(sessionLogin.logIn(login.email(), login.password(), request, response));
    }

    @GetMapping("/api/auth/me")
    @Operation(summary = "The signed-in user")
    UserResponse getCurrentUser(@AuthenticationPrincipal @Nullable AuthenticatedUser user) {
        if (user == null) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated DevMind user");
        }
        return UserResponse.of(user);
    }

    /** The token is deferred; reading it writes the cookie. The SPA calls this on start-up and after logout. */
    @GetMapping(SecurityConfiguration.CSRF_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Issue the XSRF-TOKEN cookie")
    void issueCsrfToken(@Parameter(hidden = true) CsrfToken token) {
        token.getToken();
    }
}
