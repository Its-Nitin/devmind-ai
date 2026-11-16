package com.devmind.ingestion.internal.domain;

import com.devmind.ingestion.SourceProblem;
import java.io.Serial;

/**
 * Raised inside the ingestion module when a source can't be opened, and turned into {@link
 * com.devmind.ingestion.SnapshotOutcome.Failed} at the module boundary, so it never reaches another module. Carries
 * no message beyond the problem code: paths and URLs stay out of exceptions that might be logged elsewhere.
 */
public final class SourceRejection extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final SourceProblem problem;

    /** A rejection of the input itself, e.g. a path outside the roots: nothing went wrong, so no stack trace. */
    public SourceRejection(SourceProblem problem) {
        super(problem.code(), null, false, false);
        this.problem = problem;
    }

    /** A rejection caused by a fault, e.g. an I/O error; {@code cause} is kept for the log. */
    public SourceRejection(SourceProblem problem, Throwable cause) {
        super(problem.code(), cause, false, false);
        this.problem = problem;
    }

    /** Returns why the source was rejected. */
    public SourceProblem problem() {
        return problem;
    }
}
