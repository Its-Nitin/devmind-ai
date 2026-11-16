package com.devmind.ingestion;

import java.util.stream.Stream;

/**
 * The files of one commit, read from the Git object database. Holds an open repository (and, for remote sources, a
 * clone directory) until closed, so use it in try-with-resources. Not thread-safe.
 */
public interface SourceSnapshot extends AutoCloseable {

    /** Returns the resolved commit SHA: 40 lowercase hex characters. */
    String commitSha();

    /** Returns how many tracked entries were kept and skipped at this commit. */
    SnapshotStats stats();

    /**
     * Streams the commit's regular files in path order, loading each file's content only when the stream reaches it.
     * The stream yields exactly {@link SnapshotStats#files()} elements. Consume it on one thread, before {@link
     * #close()}.
     *
     * @throws IllegalStateException if the snapshot is closed
     * @throws java.io.UncheckedIOException from the stream if the repository can't be read
     */
    Stream<SourceFile> files();

    /** Releases the repository and deletes any clone. Idempotent; never throws. */
    @Override
    void close();
}
