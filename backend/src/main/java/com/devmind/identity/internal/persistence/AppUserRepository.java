package com.devmind.identity.internal.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Users by email. The column is {@code citext}, but a {@code varchar} parameter would make Postgres compare as
 * {@code text} (case-sensitive), so the queries cast the parameter to {@code citext} explicitly.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    @Query(value = "SELECT * FROM app_user WHERE email = CAST(:email AS citext)", nativeQuery = true)
    Optional<AppUser> findByEmail(String email);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM app_user WHERE email = CAST(:email AS citext))", nativeQuery = true)
    boolean existsByEmail(String email);
}
