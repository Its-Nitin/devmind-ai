package com.devmind.indexing.internal.web;

import com.devmind.indexing.SymbolKind;
import com.devmind.indexing.internal.application.RevisionReadService.DirectoryListing;
import com.devmind.indexing.internal.application.RevisionReadService.SourceSlice;
import com.devmind.indexing.internal.application.RevisionReadService.SymbolSearch;
import com.devmind.indexing.internal.application.RevisionReadService.SymbolView;
import com.devmind.indexing.internal.domain.RevisionStats;
import com.devmind.indexing.internal.persistence.RevisionReadRows.DirectoryEntry;
import com.devmind.indexing.internal.persistence.RevisionReadRows.RevisionRow;
import com.devmind.indexing.internal.persistence.RevisionReadRows.SymbolSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.web.PagedModel;

/** Response bodies of {@link RevisionController} (API-1). Every revision-scoped one echoes the full SHA (API-7). */
final class RevisionResponses {

    private RevisionResponses() {}

    /** Matches the {@code repository_revision.status} CHECK constraint. */
    enum RevisionStatusResponse {
        INDEXING,
        READY,
        FAILED
    }

    /**
     * An indexed (or indexing) commit.
     *
     * @param readyAt null until READY
     * @param stats null until a run has finished
     */
    record RevisionResponse(
            String commitSha,
            RevisionStatusResponse status,
            Instant createdAt,
            @Nullable Instant readyAt,
            @Nullable RevisionStatsResponse stats) {

        static RevisionResponse of(RevisionRow row) {
            RevisionStats stats = row.stats();
            return new RevisionResponse(
                    row.commitSha(),
                    RevisionStatusResponse.valueOf(row.status().name()),
                    row.createdAt(),
                    row.readyAt(),
                    stats == null ? null : RevisionStatsResponse.of(stats));
        }
    }

    /** What an index run stored; {@code blobsNew} and {@code blobsReused} describe its last attempt. */
    record RevisionStatsResponse(
            long filesKept,
            Map<String, Integer> excluded,
            int blobsReused,
            int blobsNew,
            long symbols,
            long chunks,
            long parseFailures,
            long redactions,
            int encodingFallbacks,
            long durationMs) {

        static RevisionStatsResponse of(RevisionStats stats) {
            return new RevisionStatsResponse(
                    stats.filesKept(),
                    stats.excluded(),
                    stats.blobsReused(),
                    stats.blobsNew(),
                    stats.symbols(),
                    stats.chunks(),
                    stats.parseFailures(),
                    stats.redactions(),
                    stats.encodingFallbacks(),
                    stats.durationMs());
        }
    }

    enum EntryType {
        DIRECTORY,
        FILE
    }

    /** @param path the entry's full path, for the next listing or the file read */
    record EntryResponse(String name, String path, EntryType type) {}

    /**
     * A directory's direct children: directories first, then files.
     *
     * @param path the listed directory; {@code ""} for the root
     * @param truncated whether there were more entries than the listing holds
     */
    record DirectoryResponse(String commitSha, String path, List<EntryResponse> entries, boolean truncated) {

        static DirectoryResponse of(DirectoryListing listing) {
            return new DirectoryResponse(
                    listing.commitSha(),
                    listing.path(),
                    listing.entries().stream()
                            .map(entry -> entry(listing.path(), entry))
                            .toList(),
                    listing.truncated());
        }

        private static EntryResponse entry(String directory, DirectoryEntry entry) {
            String path = directory.isEmpty() ? entry.name() : directory + "/" + entry.name();
            return new EntryResponse(entry.name(), path, entry.directory() ? EntryType.DIRECTORY : EntryType.FILE);
        }
    }

    /**
     * Lines {@code from} to {@code to} of a file, LF-separated, as stored: secrets are already redacted (SEC-5).
     *
     * @param from first line returned, 1-based; 0 for an empty file
     * @param to last line returned, inclusive; 0 for an empty file
     */
    record FileSliceResponse(
            String commitSha, String path, String language, int lineCount, int from, int to, String content) {

        static FileSliceResponse of(SourceSlice slice) {
            return new FileSliceResponse(
                    slice.commitSha(),
                    slice.path(),
                    slice.language(),
                    slice.lineCount(),
                    slice.from(),
                    slice.to(),
                    slice.content());
        }
    }

    /** A symbol at one path of the revision; lines are 1-based and inclusive. */
    record SymbolResponse(
            UUID symbolId,
            SymbolKind kind,
            String name,
            String qualifiedName,
            @Nullable String signature,
            String path,
            int startLine,
            int endLine) {

        static SymbolResponse of(SymbolSummary symbol) {
            return new SymbolResponse(
                    symbol.publicId(),
                    symbol.kind(),
                    symbol.name(),
                    symbol.qualifiedName(),
                    symbol.signature(),
                    symbol.path(),
                    symbol.startLine(),
                    symbol.endLine());
        }
    }

    /** One page of search results, best first. */
    record SymbolSearchResponse(String commitSha, List<SymbolResponse> content, PagedModel.PageMetadata page) {

        static SymbolSearchResponse of(SymbolSearch search) {
            return new SymbolSearchResponse(
                    search.commitSha(),
                    search.results().map(SymbolResponse::of).getContent(),
                    new PagedModel<>(search.results()).getMetadata());
        }
    }

    /**
     * A symbol with its modifiers, annotations and direct members.
     *
     * @param parentId the enclosing type's symbol ID; null for a top-level type
     */
    record SymbolDetailResponse(
            String commitSha,
            SymbolResponse symbol,
            List<String> modifiers,
            List<String> annotations,
            boolean test,
            @Nullable UUID parentId,
            List<SymbolResponse> children) {

        static SymbolDetailResponse of(SymbolView view) {
            return new SymbolDetailResponse(
                    view.commitSha(),
                    SymbolResponse.of(view.detail().summary()),
                    view.detail().modifiers(),
                    view.detail().annotations(),
                    view.detail().test(),
                    view.detail().parentId(),
                    view.children().stream().map(SymbolResponse::of).toList());
        }
    }
}
