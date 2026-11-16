package com.devmind.ingestion.internal.git;

import com.devmind.ingestion.SourceSnapshot;
import com.devmind.ingestion.internal.domain.RefName;
import com.devmind.ingestion.internal.domain.SnapshotLimits;
import com.devmind.ingestion.internal.domain.SourceRejection;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.lib.Repository;

/** The Git adapter's entry point: opens snapshots of local and remote repositories. */
public final class GitSources {

    private final LocalRepositories local;
    private final RemoteCloner remote;
    private final SnapshotLimits limits;

    /**
     * Installs {@link JGitIsolation}, checks the allowed roots and sweeps the clone workspace.
     *
     * @param allowedRoots absolute directories that local sources must lie in; empty rejects every local source
     * @param cloneDirectory where remote clones are made; created if missing
     * @throws IllegalStateException if an allowed root is relative or doesn't exist
     * @throws java.io.UncheckedIOException if the clone directory can't be prepared
     */
    public GitSources(List<Path> allowedRoots, Path cloneDirectory, SnapshotLimits limits) {
        JGitIsolation.install();
        this.local = new LocalRepositories(allowedRoots);
        this.remote = new RemoteCloner(new CloneWorkspace(cloneDirectory), limits);
        this.limits = limits;
    }

    /**
     * Opens {@code ref} of the repository at {@code location}, which must lie inside an allowed root (SEC-2).
     *
     * @throws SourceRejection if the location or ref is rejected, a limit is exceeded, or the repository can't be read
     */
    public SourceSnapshot openLocal(String location, RefName ref) throws SourceRejection {
        Repository repository = local.open(location);
        return CommitSnapshots.open(repository, ref, limits, repository::close);
    }

    /**
     * Clones {@code uri} and opens {@code ref} (SEC-3). The snapshot deletes the clone when closed.
     *
     * @param uri already validated against the remote URL policy
     * @throws SourceRejection if the ref is rejected, a limit is exceeded, or the repository can't be reached
     */
    public SourceSnapshot openRemote(URI uri, RefName ref) throws SourceRejection {
        ClonedRepository clone = remote.clone(uri.toString(), ref);
        return CommitSnapshots.open(clone.repository(), ref, limits, clone::close);
    }
}
