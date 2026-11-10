package com.devmind.identity.internal.application;

import com.devmind.identity.CurrentUser;
import com.devmind.identity.UserId;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Reads the user from the request's security context. That context is bound to the request's thread, so a singleton
 * is enough; a request-scoped proxy would add nothing.
 */
@Component
class SecurityContextCurrentUser implements CurrentUser {

    @Override
    public UserId id() {
        Authentication authentication =
                SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.userId();
        }
        throw new AuthenticationCredentialsNotFoundException("No authenticated DevMind user");
    }
}
