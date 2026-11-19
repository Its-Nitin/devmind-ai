package com.devmind.indexing.internal.persistence;

import com.devmind.indexing.SymbolKind;
import com.devmind.indexing.internal.domain.RevisionStats;
import com.devmind.indexing.internal.persistence.IndexingRows.RevisionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Rows read by {@link RevisionQueries}. */
public final class RevisionReadRows {

    private RevisionReadRows() {}

    /**
     * A revision as listed.
     *
     * @param stats null while there are none (an INDEXING revision has {@code {}})
     */
    public record RevisionRow(
            long id,
            String commitSha,
            RevisionStatus status,
            Instant createdAt,
            @Nullable Instant readyAt,
            @Nullable RevisionStats stats) {}

    /** A direct child of a directory: a subdirectory or a file. */
    public record DirectoryEntry(String name, boolean directory) {}

    /** A file's stored content. */
    public record FileContent(String language, int lineCount, String content) {}

    /** A symbol as a search result or a child, at one path of the revision. */
    public record SymbolSummary(
            UUID publicId,
            SymbolKind kind,
            String name,
            String qualifiedName,
            @Nullable String signature,
            String path,
            int startLine,
            int endLine) {}

    /**
     * A symbol with everything about it.
     *
     * @param id internal, to look up the children; never leaves the server (API-5)
     * @param parentId the enclosing type's public ID; null for a top-level type
     */
    public record SymbolDetail(
            long id,
            SymbolSummary summary,
            List<String> modifiers,
            List<String> annotations,
            boolean test,
            @Nullable UUID parentId) {

        public SymbolDetail {
            modifiers = List.copyOf(modifiers);
            annotations = List.copyOf(annotations);
        }
    }
}
