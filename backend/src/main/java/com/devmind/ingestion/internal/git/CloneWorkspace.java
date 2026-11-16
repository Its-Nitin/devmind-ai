package com.devmind.ingestion.internal.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The directory that holds remote clones, one temporary directory per clone (SEC-3). Clones are deleted when their
 * snapshot closes; any left by a crash are swept when the workspace is created, at startup. The directory belongs to
 * one running instance: two instances must not share it.
 */
final class CloneWorkspace {

    static final String PREFIX = "devmind-clone-";

    private static final Logger log = LoggerFactory.getLogger(CloneWorkspace.class);

    private final Path directory;

    /**
     * Creates the directory if needed and deletes clones left over from earlier runs.
     *
     * @throws UncheckedIOException if the directory can't be created or listed
     */
    CloneWorkspace(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
            sweep();
        } catch (IOException ex) {
            throw new UncheckedIOException("couldn't prepare the clone workspace " + this.directory, ex);
        }
    }

    /** Creates an empty directory for one clone. */
    Path newCloneDirectory() throws IOException {
        return Files.createTempDirectory(directory, PREFIX);
    }

    /** Deletes a clone directory without following symlinks; failures are logged, and swept at the next startup. */
    void delete(Path cloneDirectory) {
        try {
            FileUtils.delete(cloneDirectory.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
        } catch (IOException ex) {
            log.warn("Couldn't delete clone directory {}", cloneDirectory, ex);
        }
    }

    private void sweep() throws IOException {
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(directory, PREFIX + "*")) {
            for (Path clone : stale) {
                log.info("Deleting clone directory left by an earlier run: {}", clone);
                delete(clone);
            }
        }
    }
}
