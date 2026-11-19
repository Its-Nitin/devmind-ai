package com.devmind.indexing.internal.application;

import com.devmind.identity.UserId;
import com.devmind.indexing.SymbolKind;
import com.devmind.indexing.internal.domain.RevisionReadException.DirectoryNotFound;
import com.devmind.indexing.internal.domain.RevisionReadException.FileNotFound;
import com.devmind.indexing.internal.domain.RevisionReadException.LineRangeInvalid;
import com.devmind.indexing.internal.domain.RevisionReadException.RevisionAmbiguous;
import com.devmind.indexing.internal.domain.RevisionReadException.RevisionNotFound;
import com.devmind.indexing.internal.domain.RevisionReadException.RevisionNotReady;
import com.devmind.indexing.internal.domain.RevisionReadException.SymbolNotFound;
import com.devmind.indexing.internal.domain.SourceLines;
import com.devmind.indexing.internal.persistence.IndexingRows.RevisionStatus;
import com.devmind.indexing.internal.persistence.RevisionQueries;
import com.devmind.indexing.internal.persistence.RevisionReadRows.DirectoryEntry;
import com.devmind.indexing.internal.persistence.RevisionReadRows.FileContent;
import com.devmind.indexing.internal.persistence.RevisionReadRows.RevisionRow;
import com.devmind.indexing.internal.persistence.RevisionReadRows.SymbolDetail;
import com.devmind.indexing.internal.persistence.RevisionReadRows.SymbolSummary;
import com.devmind.ingestion.SourceFile;
import com.devmind.project.ProjectAccessPolicy;
import com.devmind.project.ProjectId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads of indexed revisions (M2 step 8). Every method checks ownership first (SEC-1), reads one explicit revision
 * (DB-7) and only a READY one, and echoes the full commit SHA (API-7). A revision is named by its SHA or a prefix of at
 * least 7 characters that's unambiguous within the project.
 */
@Service
public class RevisionReadService {

    /** Lines returned per call at most; read longer files in ranges. */
    public static final int MAX_LINES = 2000;

    /** Directory entries returned at most; the listing says when there were more. */
    public static final int MAX_DIRECTORY_ENTRIES = 1000;

    /** pg_trgm similarity a fuzzy name match needs (M2 step 8). */
    static final double SIMILARITY_THRESHOLD = 0.4;

    private final ProjectAccessPolicy accessPolicy;
    private final RevisionQueries queries;

    RevisionReadService(ProjectAccessPolicy accessPolicy, RevisionQueries queries) {
        this.accessPolicy = accessPolicy;
        this.queries = queries;
    }

    /** A directory's direct children: subdirectories first, then files, each group by name. */
    public record DirectoryListing(String commitSha, String path, List<DirectoryEntry> entries, boolean truncated) {

        public DirectoryListing {
            entries = List.copyOf(entries);
        }
    }

    /** Lines {@code from} to {@code to} of a file; both 0 for an empty file. */
    public record SourceSlice(
            String commitSha, String path, String language, int lineCount, int from, int to, String content) {}

    /** A page of symbol search results, in one revision. */
    public record SymbolSearch(String commitSha, Page<SymbolSummary> results) {}

    /** A symbol and its direct members. */
    public record SymbolView(String commitSha, SymbolDetail detail, List<SymbolSummary> children) {

        public SymbolView {
            children = List.copyOf(children);
        }
    }

    /** Returns the project's revisions, newest first, in every status. */
    @Transactional(readOnly = true)
    public Page<RevisionRow> listRevisions(UserId user, ProjectId project, Pageable page) {
        long projectId = accessPolicy.requireOwner(user, project);
        return new PageImpl<>(
                queries.listRevisions(projectId, page.getPageSize(), page.getOffset()),
                page,
                queries.countRevisions(projectId));
    }

    /**
     * Lists a directory.
     *
     * @param path {@code ""} or {@code /} for the root; leading and trailing slashes are ignored
     * @throws DirectoryNotFound if the revision has nothing under {@code path}
     */
    @Transactional(readOnly = true)
    public DirectoryListing listDirectory(UserId user, ProjectId project, String sha, String path) {
        RevisionRow revision = readableRevision(accessPolicy.requireOwner(user, project), sha);
        String directory = stripSlashes(path);
        if (!directory.isEmpty() && !SourceFile.isValidPath(directory)) {
            throw new DirectoryNotFound();
        }
        List<DirectoryEntry> entries = queries.listDirectory(revision.id(), directory, MAX_DIRECTORY_ENTRIES + 1);
        if (entries.isEmpty() && !directory.isEmpty()) {
            throw new DirectoryNotFound(); // also when the path is a file
        }
        boolean truncated = entries.size() > MAX_DIRECTORY_ENTRIES;
        return new DirectoryListing(
                revision.commitSha(),
                directory,
                truncated ? entries.subList(0, MAX_DIRECTORY_ENTRIES) : entries,
                truncated);
    }

    /**
     * Reads lines of a file. Without {@code from}, reading starts at line 1; without {@code to}, it runs to the end of
     * the file or {@link #MAX_LINES} lines, whichever comes first. A {@code to} past the end is cut to the end.
     *
     * @throws FileNotFound if the revision has no file at {@code path}
     * @throws LineRangeInvalid if {@code from} is past the end, {@code to < from}, or the range spans more than {@link
     *     #MAX_LINES} lines
     */
    @Transactional(readOnly = true)
    public SourceSlice readFile(
            UserId user, ProjectId project, String sha, String path, @Nullable Integer from, @Nullable Integer to) {
        RevisionRow revision = readableRevision(accessPolicy.requireOwner(user, project), sha);
        if (!SourceFile.isValidPath(path)) {
            throw new FileNotFound();
        }
        FileContent file = queries.fileContent(revision.id(), path).orElseThrow(FileNotFound::new);
        int lineCount = file.lineCount();
        if (lineCount == 0) {
            if (from != null || to != null) {
                throw new LineRangeInvalid("The file is empty.");
            }
            return new SourceSlice(revision.commitSha(), path, file.language(), 0, 0, 0, "");
        }
        int first = from == null ? 1 : from;
        if (first > lineCount) {
            throw new LineRangeInvalid("The range starts past the end of the file (" + lineCount + " lines).");
        }
        int last = to == null ? Math.min(lineCount, first + MAX_LINES - 1) : Math.min(to, lineCount);
        if (last < first) {
            throw new LineRangeInvalid("The range ends before it starts.");
        }
        if (last - first + 1 > MAX_LINES) {
            throw new LineRangeInvalid("A range spans at most " + MAX_LINES + " lines.");
        }
        return new SourceSlice(
                revision.commitSha(),
                path,
                file.language(),
                lineCount,
                first,
                last,
                SourceLines.slice(file.content(), first, last));
    }

    /**
     * Finds symbols by name: exact matches of the name or qualified name first, then names similar by trigram, most
     * similar first.
     *
     * @param kind null for every kind
     */
    @Transactional(readOnly = true)
    public SymbolSearch searchSymbols(
            UserId user, ProjectId project, String sha, String q, @Nullable SymbolKind kind, Pageable page) {
        RevisionRow revision = readableRevision(accessPolicy.requireOwner(user, project), sha);
        queries.useSimilarityThreshold(SIMILARITY_THRESHOLD); // this transaction only
        List<SymbolSummary> results =
                queries.searchSymbols(revision.id(), q, kind, page.getPageSize(), page.getOffset());
        return new SymbolSearch(
                revision.commitSha(), new PageImpl<>(results, page, queries.countSymbols(revision.id(), q, kind)));
    }

    /**
     * Returns a symbol of the revision and its direct members.
     *
     * @throws SymbolNotFound if no symbol with this ID is in the revision
     */
    @Transactional(readOnly = true)
    public SymbolView symbol(UserId user, ProjectId project, String sha, UUID symbolId) {
        RevisionRow revision = readableRevision(accessPolicy.requireOwner(user, project), sha);
        SymbolDetail detail = queries.symbolDetail(revision.id(), symbolId).orElseThrow(SymbolNotFound::new);
        return new SymbolView(
                revision.commitSha(),
                detail,
                queries.symbolChildren(detail.id(), detail.summary().path()));
    }

    /**
     * The READY revision of the commit named by {@code sha}, a full SHA or a prefix. If the commit was indexed under
     * several index configs, the most recently READY one.
     *
     * @throws RevisionNotFound if no revision matches
     * @throws RevisionAmbiguous if the prefix matches more than one commit
     * @throws RevisionNotReady if the commit's revisions are all INDEXING or FAILED
     */
    private RevisionRow readableRevision(long projectId, String sha) {
        List<RevisionRow> matches = queries.matchRevisions(projectId, sha);
        if (matches.isEmpty()) {
            throw new RevisionNotFound();
        }
        if (matches.stream().map(RevisionRow::commitSha).distinct().count() > 1) {
            throw new RevisionAmbiguous();
        }
        return matches.stream()
                .filter(revision -> revision.status() == RevisionStatus.READY)
                .findFirst()
                .orElseThrow(RevisionNotReady::new);
    }

    private static String stripSlashes(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }
}
