package com.devmind.identity.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login credentials (API-2). The email format isn't checked here: an unknown email and a malformed one should fail the
 * same way, with 401 invalid-credentials.
 *
 * @param password at most 72 characters, bcrypt's input limit
 */
record LoginRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 72) String password) {}
