package com.devmind.ingestion.internal.git;

import com.devmind.ingestion.SnapshotStats;
import com.devmind.ingestion.SourceFile;
import com.devmind.ingestion.SourceProblem;
import com.devmind.ingestion.SourceSnapshot;
import com.devmind.ingestion.internal.domain.RefName;
import com.devmind.ingestion.internal.domain.SnapshotLimits;
import com.devmind.ingestion.internal.domain.SourceRejection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;

/** Resolves a ref in an open repository and lists the commit's files from its tree, never from a working tree. */
final class CommitSnapshots {

    private CommitSnapshots() {}

    /**
     * Opens a snapshot of {@code ref}. It takes ownership of {@code release}, which frees the repository: the snapshot
     * runs it on close, or this method runs it before throwing.
     *
     * @throws SourceRejection {@code REF_NOT_FOUND}, {@code REF_AMBIGUOUS}, {@code TOO_MANY_FILES}, or {@code
     *     UNREACHABLE} if the repository can't be read
     */
    static SourceSnapshot open(Repository repository, RefName ref, SnapshotLimits limits, Runnable release)
            throws SourceRejection {
        ObjectReader reader = repository.newObjectReader();
        boolean opened = false;
        try {
            RevCommit commit = resolve(repository, reader, ref);
            Tree tree = list(reader, commit, limits);
            GitSnapshot snapshot = new GitSnapshot(commit.name(), tree.stats(), tree.entries(), reader, release);
            opened = true;
            return snapshot;
        } catch (IOException ex) {
            throw new SourceRejection(SourceProblem.UNREACHABLE, ex); // corrupt or unreadable repository
        } finally {
            if (!opened) {
                reader.close();
                release.run();
            }
        }
    }

    /** Exactly {@code refs/heads/<ref>}, then {@code refs/tags/<ref>}, then a commit SHA prefix, as Git orders them. */
    private static RevCommit resolve(Repository repository, ObjectReader reader, RefName ref)
            throws IOException, SourceRejection {
        ObjectId target = refTarget(repository, Constants.R_HEADS + ref.value());
        if (target == null) {
            target = refTarget(repository, Constants.R_TAGS + ref.value());
        }
        if (target == null && ref.mayBeSha()) {
            target = uniqueObject(reader, ref.value());
        }
        if (target == null) {
            throw new SourceRejection(SourceProblem.REF_NOT_FOUND);
        }
        try (RevWalk walk = new RevWalk(reader)) {
            return walk.parseCommit(target); // peels annotated tags
        } catch (MissingObjectException | IncorrectObjectTypeException ex) {
            throw new SourceRejection(SourceProblem.REF_NOT_FOUND); // e.g. a tag of a tree, or a shallow boundary
        }
    }

    private static @Nullable ObjectId refTarget(Repository repository, String name) throws IOException {
        Ref found = repository.exactRef(name);
        return found == null ? null : found.getObjectId();
    }

    private static @Nullable ObjectId uniqueObject(ObjectReader reader, String shaPrefix)
            throws IOException, SourceRejection {
        Collection<ObjectId> matches = reader.resolve(AbbreviatedObjectId.fromString(shaPrefix));
        if (matches.size() > 1) {
            throw new SourceRejection(SourceProblem.REF_AMBIGUOUS);
        }
        return matches.isEmpty() ? null : matches.iterator().next();
    }

    private static Tree list(ObjectReader reader, RevCommit commit, SnapshotLimits limits)
            throws IOException, SourceRejection {
        List<GitSnapshot.Entry> entries = new ArrayList<>();
        int seen = 0;
        int symlinks = 0;
        int submodules = 0;
        int oversized = 0;
        int invalidPaths = 0;
        try (TreeWalk walk = new TreeWalk(reader)) {
            walk.addTree(commit.getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                if (++seen > limits.maxFiles()) {
                    throw new SourceRejection(SourceProblem.TOO_MANY_FILES);
                }
                switch (walk.getRawMode(0) & FileMode.TYPE_MASK) {
                    case FileMode.TYPE_FILE -> { // regular and executable files
                        ObjectId blob = walk.getObjectId(0);
                        String path = walk.getPathString(); // '/'-separated, as Git stores it (JAVA-10)
                        if (!SourceFile.isValidPath(path)) {
                            invalidPaths++; // too long, or malformed by a crafted repository
                        } else if (reader.getObjectSize(blob, Constants.OBJ_BLOB) > limits.maxFileBytes()) {
                            oversized++; // the size comes from the object header; the content isn't loaded
                        } else {
                            entries.add(new GitSnapshot.Entry(path, blob));
                        }
                    }
                    case FileMode.TYPE_SYMLINK -> symlinks++;
                    case FileMode.TYPE_GITLINK -> submodules++;
                    default -> throw new IOException("unexpected tree entry mode"); // corrupt tree
                }
            }
        }
        return new Tree(entries, new SnapshotStats(entries.size(), symlinks, submodules, oversized, invalidPaths));
    }

    private record Tree(List<GitSnapshot.Entry> entries, SnapshotStats stats) {}
}
