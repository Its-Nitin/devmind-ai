package com.devmind.indexing.internal.application;

import com.devmind.job.JobContext;
import com.devmind.job.JobId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A {@link JobContext} for running a handler directly; can simulate a worker dying after N heartbeats. */
final class FakeJobContext implements JobContext {

    private final JobId jobId = new JobId(UUID.randomUUID());
    private final long projectId;
    private final Map<String, Object> payload;
    private final int crashAtHeartbeat;
    private final List<Long> progress = new ArrayList<>();
    private int heartbeats;

    private FakeJobContext(long projectId, Map<String, Object> payload, int crashAtHeartbeat) {
        this.projectId = projectId;
        this.payload = Map.copyOf(payload);
        this.crashAtHeartbeat = crashAtHeartbeat;
    }

    static FakeJobContext of(long projectId, Map<String, Object> payload) {
        return new FakeJobContext(projectId, payload, 0);
    }

    /** Throws from the {@code n}th heartbeat, as if the worker died right after that batch committed. */
    static FakeJobContext crashingAt(int n, long projectId, Map<String, Object> payload) {
        return new FakeJobContext(projectId, payload, n);
    }

    /** The {@code done} value of every progress report, in order. */
    List<Long> progress() {
        return List.copyOf(progress);
    }

    @Override
    public JobId jobId() {
        return jobId;
    }

    @Override
    public long projectId() {
        return projectId;
    }

    @Override
    public int attempt() {
        return 1;
    }

    @Override
    public Map<String, Object> payload() {
        return payload;
    }

    @Override
    public void progress(String phase, long done, long total) {
        progress.add(done);
    }

    @Override
    public boolean heartbeat() {
        heartbeats++;
        if (heartbeats == crashAtHeartbeat) {
            throw new IllegalStateException("simulated worker crash");
        }
        return true;
    }
}
