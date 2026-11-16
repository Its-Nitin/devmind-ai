package com.devmind.ingestion.internal.git;

import com.devmind.ingestion.SourceProblem;
import com.devmind.ingestion.internal.domain.RefName;
import com.devmind.ingestion.internal.domain.SnapshotLimits;
import com.devmind.ingestion.internal.domain.SourceRejection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.SubmoduleConfig.FetchRecurseSubmodulesMode;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.jspecify.annotations.Nullable;

/**
 * Clones a remote repository into a fresh bare repository in the {@link CloneWorkspace}, within the {@link
 * SnapshotLimits} (SEC-3, threats T2/T3/T14). Bare, so nothing is ever checked out and no filter or hook can run.
 *
 * <p>The URL must already be validated ({@code RemoteUrlPolicy}); this class trusts its caller on that and is tested
 * against local {@code file://} repositories, so tests need no network (TEST-5).
 */
final class RemoteCloner {

    private static final List<RefSpec> ALL_BRANCHES_AND_TAGS =
            List.of(new RefSpec("+refs/heads/*:refs/heads/*"), new RefSpec("+refs/tags/*:refs/tags/*"));

    private final CloneWorkspace workspace;
    private final SnapshotLimits limits;

    RemoteCloner(CloneWorkspace workspace, SnapshotLimits limits) {
        this.workspace = workspace;
        this.limits = limits;
    }

    /**
     * Clones just enough of {@code uri} to read {@code ref}: a branch or tag at depth 1, or, for a commit SHA, every
     * branch and tag with full history (fetching a single SHA arrives with M5). The caller closes the result.
     *
     * @throws SourceRejection {@code REF_NOT_FOUND}, {@code LOCATION_NOT_FOUND}, {@code TOO_LARGE}, {@code TIMED_OUT}
     *     or {@code UNREACHABLE}; the clone directory is already deleted
     */
    ClonedRepository clone(String uri, RefName ref) throws SourceRejection {
        Path directory;
        try {
            directory = workspace.newCloneDirectory();
        } catch (IOException ex) {
            throw new SourceRejection(SourceProblem.UNREACHABLE, ex);
        }
        Repository repository = null;
        boolean cloned = false;
        try {
            repository = initBare(directory);
            CloneGuard guard = new CloneGuard(directory, limits.maxCloneBytes(), limits.cloneTimeout());
            fetch(repository, uri, ref, guard);
            guard.verifyFinished();
            cloned = true;
            return new ClonedRepository(directory, repository, workspace);
        } finally {
            if (!cloned) {
                if (repository != null) {
                    repository.close();
                }
                workspace.delete(directory);
            }
        }
    }

    private static Repository initBare(Path directory) throws SourceRejection {
        try {
            Repository repository = new FileRepositoryBuilder()
                    .setGitDir(directory.toFile())
                    .setBare()
                    .build();
            repository.create(true);
            StoredConfig config = repository.getConfig();
            // A redirect could lead to a host the allow-list never saw (SEC-3), so none is followed.
            config.setString("http", null, "followRedirects", "false");
            // No auto-gc after the fetch: it runs on a background thread, wasted on a throwaway clone, and races the
            // clone's deletion.
            config.setInt("gc", null, "auto", 0);
            config.setBoolean("gc", null, "autoDetach", false);
            config.save();
            return repository;
        } catch (IOException ex) {
            throw new SourceRejection(SourceProblem.UNREACHABLE, ex);
        }
    }

    private void fetch(Repository repository, String uri, RefName ref, CloneGuard guard) throws SourceRejection {
        int timeoutSeconds = Math.clamp(limits.cloneTimeout().toSeconds(), 1, Integer.MAX_VALUE);
        try (Git git = Git.wrap(repository)) { // wrap: closing Git leaves the repository open
            // List refs first, so a missing ref fails before anything is downloaded.
            FetchPlan plan = plan(
                    git.lsRemote().setRemote(uri).setTimeout(timeoutSeconds).call(), ref);
            FetchCommand fetch = git.fetch()
                    .setRemote(uri)
                    .setRefSpecs(plan.refSpecs())
                    .setTagOpt(TagOpt.NO_TAGS)
                    .setRecurseSubmodules(FetchRecurseSubmodulesMode.NO)
                    .setTimeout(timeoutSeconds)
                    .setProgressMonitor(guard);
            if (plan.shallow()) {
                fetch.setDepth(1);
            }
            fetch.call();
        } catch (GitAPIException | JGitInternalException ex) {
            throw new SourceRejection(guard.tripped().orElseGet(() -> problemOf(ex)), ex);
        }
    }

    private record FetchPlan(List<RefSpec> refSpecs, boolean shallow) {}

    /** Just the branch or tag named {@code ref} (branches win, as in Git), else everything if it may be a SHA. */
    private static FetchPlan plan(Collection<Ref> advertised, RefName ref) throws SourceRejection {
        String name = namedRef(advertised, ref);
        if (name != null) {
            return new FetchPlan(List.of(new RefSpec("+" + name + ":" + name)), true);
        }
        if (ref.mayBeSha()) {
            return new FetchPlan(ALL_BRANCHES_AND_TAGS, false);
        }
        throw new SourceRejection(SourceProblem.REF_NOT_FOUND);
    }

    private static @Nullable String namedRef(Collection<Ref> advertised, RefName ref) {
        Set<String> names = advertised.stream().map(Ref::getName).collect(Collectors.toSet());
        for (String prefix : List.of(Constants.R_HEADS, Constants.R_TAGS)) {
            if (names.contains(prefix + ref.value())) {
                return prefix + ref.value();
            }
        }
        return null;
    }

    private static SourceProblem problemOf(Exception ex) {
        if (ex instanceof InvalidRemoteException) {
            return SourceProblem.LOCATION_NOT_FOUND;
        }
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof NoRemoteRepositoryException) {
                return SourceProblem.LOCATION_NOT_FOUND;
            }
        }
        return SourceProblem.UNREACHABLE;
    }
}
