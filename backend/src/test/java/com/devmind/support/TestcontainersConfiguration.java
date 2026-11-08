package com.devmind.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres + pgvector for integration tests. Same pinned image as {@code deploy/compose.yaml} (TEST-4); never H2.
 * Locally, containers are reused when {@code testcontainers.reuse.enable=true} is set in
 * {@code ~/.testcontainers.properties}; CI never reuses.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Exact pgvector + Postgres major. Change together with deploy/compose.yaml. */
    public static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg17").asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(PGVECTOR).withReuse(true);
    }
}
