package com.devmind.ingestion;

/** Opens a project's source at one commit. Implemented by the ingestion module; used by indexing (M2) and impact (M5). */
public interface SourceSnapshotProvider {

    /**
     * Resolves {@code ref} to a commit and opens the files tracked at that commit. Files are read from the Git object
     * database, never from a working tree, so uncommitted changes, untracked files, filters and hooks play no part.
     *
     * <p>Opening a remote source clones it, which blocks for up to the configured clone timeout (ERR-6). Never call
     * this inside a database transaction (SPRING-2).
     *
     * @param ref a branch or tag name, or a commit SHA (40 hex characters, or an unambiguous prefix of at least 7)
     * @return the opened snapshot, which the caller must close; or the problem that prevented opening it. Never null,
     *     and never throws for invalid input, rejected sources or unreachable repositories.
     */
    SnapshotOutcome open(ProjectSource source, String ref);
}
