package com.devmind.identity.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A row of {@code app_user} (V1). Never leaves the identity module (ADR-0005, API-1). */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "display_name", nullable = false, columnDefinition = "text")
    private String displayName;

    @Column(name = "password_hash", nullable = false, columnDefinition = "text")
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected AppUser() {}

    public AppUser(UUID publicId, String email, String displayName, String passwordHash, Instant createdAt) {
        this.publicId = publicId;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public UUID publicId() {
        return publicId;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    /** The encoded password, with its {@code {id}} prefix (DelegatingPasswordEncoder). */
    public String passwordHash() {
        return passwordHash;
    }
}
