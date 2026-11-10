package com.devmind.identity.internal.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code local} profile's demo user (SPRING-1). The password comes only from the {@code DEVMIND_SEED_PASSWORD}
 * env var (SEC-6), and startup fails if it's missing or too short.
 *
 * @param password 12 to 72 characters; 72 is bcrypt's input limit
 */
@Profile("local")
@Validated
@ConfigurationProperties("devmind.identity.seed")
record DemoUserProperties(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 100) String displayName,
        @NotBlank @Size(min = 12, max = 72) String password) {}
