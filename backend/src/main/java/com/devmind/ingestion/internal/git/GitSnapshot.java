package com.devmind.ingestion.internal.git;

import com.devmind.ingestion.SnapshotStats;
import com.devmind.ingestion.SourceFile;
import com.devmind.ingestion.SourceSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A commit's file list, with blob content loaded on demand from one shared object reader. */
final class GitSnapshot implements SourceSnapshot {

    private static final Logger log = LoggerFactory.getLogger(GitSnapshot.class);

    /** A file to load: its path and blob. */
    record Entry(String path, ObjectId blob) {}

    private final String commitSha;
    private final SnapshotStats stats;
    private final List<Entry> entries;
    private final ObjectReader reader;
    private final Runnable release;
    private boolean closed;

    /**
     * @param reader owned by the snapshot, closed by {@link #close()}
     * @param release frees the repository (and deletes a clone); run once, on close
     */
    GitSnapshot(String commitSha, SnapshotStats stats, List<Entry> entries, ObjectReader reader, Runnable release) {
        this.commitSha = commitSha;
        this.stats = stats;
        this.entries = List.copyOf(entries);
        this.reader = reader;
        this.release = release;
    }

    @Override
    public String commitSha() {
        return commitSha;
    }

    @Override
    public SnapshotStats stats() {
        return stats;
    }

    @Override
    public Stream<SourceFile> files() {
        requireOpen();
        return entries.stream().map(this::load);
    }

    private SourceFile load(Entry entry) {
        requireOpen();
        try {
            return new SourceFile(
                    entry.path(), reader.open(entry.blob(), Constants.OBJ_BLOB).getBytes());
        } catch (IOException ex) {
            throw new UncheckedIOException("couldn't read a blob of commit " + commitSha, ex);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("snapshot is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        reader.close();
        try {
            release.run();
        } catch (RuntimeException ex) {
            // close() never throws (SourceSnapshot contract); a leftover clone is swept at the next startup.
            log.warn("Releasing the snapshot of commit {} failed", commitSha, ex);
        }
    }
}
