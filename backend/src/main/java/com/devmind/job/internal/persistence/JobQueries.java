package com.devmind.job.internal.persistence;

import com.devmind.job.JobId;
import com.devmind.job.JobProgress;
import com.devmind.job.JobStatus;
import com.devmind.job.JobType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every statement on {@code job} (ADR-0009). Each is a single statement, atomic on its own, so none needs a
 * transaction. Writes made while running a job are guarded by its {@link Lease} and report whether they applied.
 * Timestamps come from the caller's {@code Clock} (JAVA-9) and are bound as UTC {@link OffsetDateTime}.
 */
@Repository
public class JobQueries {

    private static final String COLUMNS = """
            id, public_id, project_id, type, status, payload, attempts, max_attempts, progress, error_code,
            created_at, updated_at""";

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO job (public_id, project_id, type, idempotency_key, status, payload, max_attempts, run_after,
                             created_at, updated_at)
            VALUES (:publicId, :projectId, :type, :key, 'QUEUED', CAST(:payload AS jsonb), :maxAttempts, :now,
                    :now, :now)
            ON CONFLICT (project_id, type, idempotency_key) WHERE status <> 'FAILED' DO NOTHING""";

    private static final String FIND_LIVE = "SELECT " + COLUMNS + """
             FROM job
            WHERE project_id = :projectId AND type = :type AND idempotency_key = :key AND status <> 'FAILED'""";

    private static final String FIND_BY_PUBLIC_ID = "SELECT " + COLUMNS + " FROM job WHERE public_id = :publicId";

    /*
     * One statement claims the oldest due job: QUEUED and due, or RUNNING with an expired lease (its worker died) and
     * attempts left. SKIP LOCKED lets concurrent workers pass over a row another claim is taking.
     */
    private static final String CLAIM_NEXT = """
            UPDATE job
               SET status = 'RUNNING', locked_by = :workerId, locked_until = :lockedUntil,
                   attempts = attempts + 1, updated_at = :now
             WHERE id = (
                   SELECT id FROM job
                    WHERE type IN (:types)
                      AND ((status = 'QUEUED' AND run_after <= :now)
                           OR (status = 'RUNNING' AND locked_until < :now AND attempts < max_attempts))
                    ORDER BY run_after, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED)
            RETURNING\s""" + COLUMNS;

    /** A RUNNING job whose lease expired on its last attempt can't be claimed again, so it fails here. */
    private static final String FAIL_EXPIRED_LAST_ATTEMPTS = """
            UPDATE job
               SET status = 'FAILED', locked_by = NULL, locked_until = NULL, error_code = :code,
                   error_detail = :detail, updated_at = :now
             WHERE status = 'RUNNING' AND locked_until < :now AND attempts >= max_attempts""";

    /** Appended to every write made under a lease. */
    private static final String LEASE_GUARD =
            " WHERE id = :jobId AND status = 'RUNNING' AND locked_by = :workerId AND attempts = :attempt";

    private static final String EXTEND_LEASE =
            "UPDATE job SET locked_until = :lockedUntil, updated_at = :now" + LEASE_GUARD;

    private static final String SAVE_PROGRESS =
            "UPDATE job SET progress = CAST(:progress AS jsonb), updated_at = :now" + LEASE_GUARD;

    private static final String MARK_SUCCEEDED = """
            UPDATE job
               SET status = 'SUCCEEDED', locked_by = NULL, locked_until = NULL, error_code = NULL,
                   error_detail = NULL, updated_at = :now""" + LEASE_GUARD;

    private static final String REQUEUE = """
            UPDATE job
               SET status = 'QUEUED', run_after = :runAfter, locked_by = NULL, locked_until = NULL,
                   error_code = :code, error_detail = :detail, updated_at = :now""" + LEASE_GUARD;

    private static final String MARK_FAILED = """
            UPDATE job
               SET status = 'FAILED', locked_by = NULL, locked_until = NULL, error_code = :code,
                   error_detail = :detail, updated_at = :now""" + LEASE_GUARD;

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    /**
     * The stored shape of {@code job.progress}. Its own type, so the column format doesn't depend on the public
     * {@link JobProgress} record or on mapper defaults: the column defaults to {@code {}}, whose missing fields read
     * as null here and become {@link JobProgress#NONE}'s values.
     */
    private record StoredProgress(
            @Nullable String phase,
            @Nullable Long done,
            @Nullable Long total) {

        static StoredProgress of(JobProgress progress) {
            return new StoredProgress(progress.phase(), progress.done(), progress.total());
        }

        JobProgress toProgress() {
            return new JobProgress(phase, done == null ? 0 : done, total == null ? 0 : total);
        }
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JobQueries(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Inserts a QUEUED job unless a live one has the same key; returns whether a row was inserted. */
    public boolean insertIfAbsent(
            JobId publicId,
            long projectId,
            JobType type,
            String idempotencyKey,
            Map<String, Object> payload,
            int maxAttempts,
            Instant now) {
        return jdbc.sql(INSERT_IF_ABSENT)
                        .param("publicId", publicId.value())
                        .param("projectId", projectId)
                        .param("type", type.name())
                        .param("key", idempotencyKey)
                        .param("payload", json.writeValueAsString(payload))
                        .param("maxAttempts", maxAttempts)
                        .param("now", utc(now))
                        .update()
                > 0;
    }

    /** Returns the job with this key that isn't FAILED; the partial unique index allows at most one. */
    public Optional<JobRecord> findLive(long projectId, JobType type, String idempotencyKey) {
        return jdbc.sql(FIND_LIVE)
                .param("projectId", projectId)
                .param("type", type.name())
                .param("key", idempotencyKey)
                .query(this::toRecord)
                .optional();
    }

    public Optional<JobRecord> findByPublicId(JobId publicId) {
        return jdbc.sql(FIND_BY_PUBLIC_ID)
                .param("publicId", publicId.value())
                .query(this::toRecord)
                .optional();
    }

    /**
     * Claims the oldest due job of one of {@code types} for {@code workerId}: RUNNING, attempts + 1, leased until
     * {@code lockedUntil}. Concurrent callers never get the same job.
     *
     * @param types non-empty
     */
    public Optional<JobRecord> claimNext(Collection<JobType> types, String workerId, Instant now, Instant lockedUntil) {
        if (types.isEmpty()) {
            throw new IllegalArgumentException("types must not be empty");
        }
        return jdbc.sql(CLAIM_NEXT)
                .param("types", types.stream().map(JobType::name).toList())
                .param("workerId", workerId)
                .param("now", utc(now))
                .param("lockedUntil", utc(lockedUntil))
                .query(this::toRecord)
                .optional();
    }

    /** Fails RUNNING jobs whose lease expired on their last attempt; returns how many. */
    public int failExpiredLastAttempts(Instant now, String code, String detail) {
        return jdbc.sql(FAIL_EXPIRED_LAST_ATTEMPTS)
                .param("now", utc(now))
                .param("code", code)
                .param("detail", detail)
                .update();
    }

    public boolean extendLease(Lease lease, Instant lockedUntil, Instant now) {
        return leased(EXTEND_LEASE, lease)
                        .param("lockedUntil", utc(lockedUntil))
                        .param("now", utc(now))
                        .update()
                > 0;
    }

    public boolean saveProgress(Lease lease, JobProgress progress, Instant now) {
        return leased(SAVE_PROGRESS, lease)
                        .param("progress", json.writeValueAsString(StoredProgress.of(progress)))
                        .param("now", utc(now))
                        .update()
                > 0;
    }

    public boolean markSucceeded(Lease lease, Instant now) {
        return leased(MARK_SUCCEEDED, lease).param("now", utc(now)).update() > 0;
    }

    /** Back to QUEUED until {@code runAfter}, keeping the failure visible meanwhile. */
    public boolean requeue(Lease lease, Instant runAfter, String code, String detail, Instant now) {
        return leased(REQUEUE, lease)
                        .param("runAfter", utc(runAfter))
                        .param("code", code)
                        .param("detail", detail)
                        .param("now", utc(now))
                        .update()
                > 0;
    }

    public boolean markFailed(Lease lease, String code, String detail, Instant now) {
        return leased(MARK_FAILED, lease)
                        .param("code", code)
                        .param("detail", detail)
                        .param("now", utc(now))
                        .update()
                > 0;
    }

    private JdbcClient.StatementSpec leased(String sql, Lease lease) {
        return jdbc.sql(sql)
                .param("jobId", lease.jobId())
                .param("workerId", lease.workerId())
                .param("attempt", lease.attempt());
    }

    private JobRecord toRecord(ResultSet rs, int rowNum) throws SQLException {
        return new JobRecord(
                rs.getLong("id"),
                new JobId(Objects.requireNonNull(rs.getObject("public_id", UUID.class))),
                rs.getLong("project_id"),
                JobType.valueOf(rs.getString("type")),
                JobStatus.valueOf(rs.getString("status")),
                json.readValue(rs.getString("payload"), PAYLOAD_TYPE),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                json.readValue(rs.getString("progress"), StoredProgress.class).toProgress(),
                rs.getString("error_code"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return Objects.requireNonNull(rs.getObject(column, OffsetDateTime.class))
                .toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
