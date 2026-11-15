package com.devmind.job.internal.application;

import com.devmind.job.JobId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads job rows as stored, including columns the API doesn't expose, and clears the table between job tests. */
final class JobRows {

    /** The columns job tests assert on, with the progress jsonb unpacked. */
    record JobRow(
            String status,
            int attempts,
            Instant runAfter,
            @Nullable String lockedBy,
            @Nullable String errorCode,
            @Nullable String errorDetail,
            @Nullable String progressPhase,
            long progressDone,
            long progressTotal) {}

    private final JdbcClient jdbc;

    JobRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    JobRow get(JobId id) {
        return jdbc.sql("""
                        SELECT status, attempts, run_after, locked_by, error_code, error_detail,
                               progress ->> 'phase' AS phase,
                               coalesce((progress ->> 'done')::bigint, 0) AS done,
                               coalesce((progress ->> 'total')::bigint, 0) AS total
                        FROM job WHERE public_id = ?
                        """)
                .param(id.value())
                .query((rs, rowNum) -> new JobRow(
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        Objects.requireNonNull(rs.getObject("run_after", OffsetDateTime.class))
                                .toInstant(),
                        rs.getString("locked_by"),
                        rs.getString("error_code"),
                        rs.getString("error_detail"),
                        rs.getString("phase"),
                        rs.getLong("done"),
                        rs.getLong("total")))
                .single();
    }

    /**
     * Claims are global, so tests that claim start from an empty queue. Test classes run one at a time (no parallel
     * execution is configured), so this can't remove another test's jobs mid-test.
     */
    void deleteAll() {
        jdbc.sql("DELETE FROM job").update();
    }

    /** Marks a job FAILED directly, to set up resubmission after a failure. */
    void markFailed(JobId id) {
        jdbc.sql("UPDATE job SET status = 'FAILED' WHERE public_id = ?")
                .param(id.value())
                .update();
    }
}
