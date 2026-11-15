package com.devmind.job;

import org.jspecify.annotations.Nullable;

/**
 * How far a running job has got, as last reported by its handler.
 *
 * @param phase handler-defined phase name, e.g. {@code parsing}; null before the first report
 * @param done units finished in this phase, at least 0
 * @param total units in this phase, at least 0; 0 when unknown
 */
public record JobProgress(@Nullable String phase, long done, long total) {

    /** Nothing reported yet. */
    public static final JobProgress NONE = new JobProgress(null, 0, 0);

    /** @throws IllegalArgumentException if {@code done} or {@code total} is negative */
    public JobProgress {
        if (done < 0 || total < 0) {
            throw new IllegalArgumentException("done and total must not be negative");
        }
    }
}
