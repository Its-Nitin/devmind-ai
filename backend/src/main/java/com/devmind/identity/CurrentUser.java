package com.devmind.identity;

/**
 * The user making the current request. Other modules inject this instead of touching Spring Security, then pass the
 * {@link UserId} to authorization checks such as {@code ProjectAccessPolicy}.
 */
public interface CurrentUser {

    /**
     * Returns the ID of the authenticated user making the current request, never null.
     *
     * @throws org.springframework.security.authentication.AuthenticationCredentialsNotFoundException if the request
     *     isn't authenticated as a DevMind user; it maps to a 401 ProblemDetail
     */
    UserId id();
}
