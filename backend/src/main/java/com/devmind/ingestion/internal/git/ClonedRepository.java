package com.devmind.ingestion.internal.git;

import java.nio.file.Path;
import org.eclipse.jgit.lib.Repository;

/** A bare clone on disk. Closing it closes the repository and deletes the clone directory. */
final class ClonedRepository implements AutoCloseable {

    private final Path directory;
    private final Repository repository;
    private final CloneWorkspace workspace;

    ClonedRepository(Path directory, Repository repository, CloneWorkspace workspace) {
        this.directory = directory;
        this.repository = repository;
        this.workspace = workspace;
    }

    Path directory() {
        return directory;
    }

    Repository repository() {
        return repository;
    }

    @Override
    public void close() {
        repository.close(); // before deleting: Windows can't delete open pack files
        workspace.delete(directory);
    }
}
