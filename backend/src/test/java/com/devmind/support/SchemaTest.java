package com.devmind.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Guards every future migration against schema-level rule violations (DB-2). */
@IntegrationTest
class SchemaTest {

    /**
     * Foreign keys whose columns aren't the leading columns of a valid, non-partial index. A partial index doesn't
     * cover every row, so it can't serve cascade deletes or joins on the FK.
     */
    private static final String UNINDEXED_FOREIGN_KEYS = """
            SELECT c.conrelid::regclass || '.' || c.conname AS fk
            FROM pg_constraint c
            WHERE c.contype = 'f'
              AND c.connamespace = 'public'::regnamespace
              AND NOT EXISTS (
                  SELECT 1
                  FROM pg_index i
                  WHERE i.indrelid = c.conrelid
                    AND i.indisvalid
                    AND i.indpred IS NULL
                    AND (i.indkey::int2[])[0:cardinality(c.conkey) - 1] @> c.conkey
                    AND (i.indkey::int2[])[0:cardinality(c.conkey) - 1] <@ c.conkey)
            ORDER BY fk
            """;

    private final JdbcClient jdbc;

    @Autowired
    SchemaTest(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void everyForeignKeyHasASupportingIndex() {
        List<String> unindexed =
                jdbc.sql(UNINDEXED_FOREIGN_KEYS).query(String.class).list();

        assertThat(unindexed)
                .as("DB-2: foreign keys without a supporting index")
                .isEmpty();
    }

    @Test
    void baselineMigrationCreatesExpectedTablesAndExtensions() {
        List<String> tables = jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY 1")
                .query(String.class)
                .list();
        List<String> extensions = jdbc.sql("SELECT extname FROM pg_extension ORDER BY 1")
                .query(String.class)
                .list();

        assertThat(tables).contains("app_user", "project", "job", "flyway_schema_history");
        assertThat(extensions).contains("vector", "pg_trgm", "citext");
    }
}
