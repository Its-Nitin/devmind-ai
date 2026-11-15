package com.devmind.support;

import com.devmind.identity.UserId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Inserts users and projects straight into the database, for tests below the web layer. Every call creates new rows
 * with unique values, because the test database isn't cleaned between tests.
 */
public final class TestProjects {

    /** A project and its owner; {@code id} is the internal {@code project.id}. */
    public record OwnedProject(UserId owner, long id) {}

    private final JdbcClient jdbc;

    public TestProjects(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UserId insertUser() {
        UUID publicId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user (public_id, email, display_name, password_hash, created_at)
                        VALUES (?, ?, 'Test User', 'not-a-hash', now())
                        """).params(publicId, "user-" + publicId + "@test.local").update();
        return new UserId(publicId);
    }

    public OwnedProject insertProject() {
        UserId owner = insertUser();
        long id = jdbc.sql("""
                        INSERT INTO project (public_id, owner_id, name, source_type, source_location, created_at)
                        VALUES (?, ?, 'p', 'LOCAL', '/targets/p', now())
                        RETURNING id
                        """)
                .params(UUID.randomUUID(), owner.value())
                .query(Long.class)
                .single();
        return new OwnedProject(owner, id);
    }
}
