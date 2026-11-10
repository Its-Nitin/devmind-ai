package com.devmind.identity.internal.web;

import com.devmind.identity.internal.application.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;

/**
 * Logs a user in to an HTTP session, the way Spring Security's own login filters do: authenticate, rotate the session
 * ID and the CSRF token, then save the security context in the session.
 */
@Component
class SessionLogin {

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    SessionLogin(
            AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionStrategy,
            SecurityContextRepository contextRepository) {
        this.authenticationManager = authenticationManager;
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
    }

    /**
     * @throws org.springframework.security.authentication.BadCredentialsException for an unknown email or a wrong
     *     password alike
     */
    AuthenticatedUser logIn(String email, String password, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        sessionStrategy.onAuthentication(authentication, request, response);

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        issueRotatedCsrfToken(request);

        if (!(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("Unexpected principal type");
        }
        return user;
    }

    /**
     * The session strategy cleared the old token and put a deferred new one on the request. Reading it writes the new
     * XSRF-TOKEN cookie into this response, so the SPA needn't fetch one before its next write.
     */
    private static void issueRotatedCsrfToken(HttpServletRequest request) {
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
            token.getToken();
        }
    }
}
