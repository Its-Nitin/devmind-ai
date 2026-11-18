package com.devmind.indexing;

import java.util.Objects;

/**
 * Published when a revision has been indexed and validated, so other modules can build on it (ARCH-6), e.g.
 * embeddings in M3. Published in the transaction that marks the revision {@code READY}.
 *
 * @param projectId internal project ID, for server-side joins only (API-5)
 * @param revisionId internal {@code repository_revision} ID
 * @param commitSha the indexed commit, 40 lowercase hex characters
 */
public record RevisionReadyEvent(long projectId, long revisionId, String commitSha) {

    /** @throws NullPointerException if {@code commitSha} is null */
    public RevisionReadyEvent {
        Objects.requireNonNull(commitSha, "commitSha");
    }
}
