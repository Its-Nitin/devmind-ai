package com.devmind.project.internal.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Projects, always filtered by owner: no query here returns another user's project. */
public interface ProjectRepository extends JpaRepository<Project, Long> {

    @Query("SELECT p.id FROM Project p WHERE p.publicId = :publicId AND p.ownerId = :ownerId")
    Optional<Long> findIdByPublicIdAndOwnerId(UUID publicId, UUID ownerId);

    Page<Project> findByOwnerId(UUID ownerId, Pageable pageable);

    boolean existsByOwnerIdAndName(UUID ownerId, String name);
}
