package com.devmind.project.internal.persistence;

import com.devmind.project.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A row of {@code project}. Never leaves the project module (ADR-0005, API-1). */
@Entity
@Table(name = "project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    /** The owner's public user ID ({@code app_user.public_id}, V2). */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false, columnDefinition = "text")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, columnDefinition = "text")
    private SourceType sourceType;

    @Column(name = "source_location", nullable = false, columnDefinition = "text")
    private String sourceLocation;

    @Column(name = "default_branch", columnDefinition = "text")
    private @Nullable String defaultBranch;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected Project() {}

    public Project(
            UUID publicId, UUID ownerId, String name, SourceType sourceType, String sourceLocation, Instant createdAt) {
        this.publicId = publicId;
        this.ownerId = ownerId;
        this.name = name;
        this.sourceType = sourceType;
        this.sourceLocation = sourceLocation;
        this.createdAt = createdAt;
    }

    public UUID publicId() {
        return publicId;
    }

    public String name() {
        return name;
    }

    public SourceType sourceType() {
        return sourceType;
    }

    public String sourceLocation() {
        return sourceLocation;
    }

    public @Nullable String defaultBranch() {
        return defaultBranch;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
