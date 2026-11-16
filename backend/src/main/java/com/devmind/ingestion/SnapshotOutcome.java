package com.devmind.ingestion;

import java.util.Objects;

/** The result of {@link SourceSnapshotProvider#open}: a snapshot to read, or why there is none (ERR-2, JAVA-7). */
public sealed interface SnapshotOutcome {

    /**
     * The source was opened.
     *
     * @param snapshot the caller owns it and must close it
     */
    record Opened(SourceSnapshot snapshot) implements SnapshotOutcome {

        /** @throws NullPointerException if {@code snapshot} is null */
        public Opened {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * The source couldn't be opened; nothing needs closing.
     *
     * @param problem why, never null
     */
    record Failed(SourceProblem problem) implements SnapshotOutcome {

        /** @throws NullPointerException if {@code problem} is null */
        public Failed {
            Objects.requireNonNull(problem, "problem");
        }
    }
}
