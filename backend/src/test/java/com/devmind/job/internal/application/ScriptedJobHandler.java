package com.devmind.job.internal.application;

import com.devmind.job.JobContext;
import com.devmind.job.JobHandler;
import com.devmind.job.JobOutcome;
import com.devmind.job.JobType;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Test-only handler for {@link JobType#EVAL}, the one type no production handler uses yet. Each test scripts what it
 * does; {@link #reset()} goes back to succeeding.
 */
final class ScriptedJobHandler implements JobHandler {

    static final JobType TYPE = JobType.EVAL;

    private final AtomicReference<Function<JobContext, JobOutcome>> behaviour = new AtomicReference<>();

    ScriptedJobHandler() {
        reset();
    }

    void willDo(Function<JobContext, JobOutcome> next) {
        behaviour.set(next);
    }

    void reset() {
        behaviour.set(context -> JobOutcome.succeeded());
    }

    @Override
    public JobType type() {
        return TYPE;
    }

    @Override
    public JobOutcome handle(JobContext context) {
        return behaviour.get().apply(context);
    }
}
