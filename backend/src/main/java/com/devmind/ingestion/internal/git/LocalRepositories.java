package com.devmind.ingestion.internal.git;

import com.devmind.ingestion.SourceProblem;
import com.devmind.ingestion.internal.domain.SourceRejection;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;

/**
 * Opens local repositories that lie entirely inside a server-configured allowed root (SEC-2, threat T1).
 *
 * <p>"Entirely" covers more than the directory the user named: Git can redirect reads through a {@code .git} gitfile,
 * a worktree's {@code commondir}, {@code objects/info/alternates}, or symlinks inside the Git directory. Each of those
 * is either resolved and checked against the roots or rejected. Files are then read from the object database only, so
 * symlinks in the working tree are never followed.
 */
final class LocalRepositories {

    private static final List<String> ALTERNATES = List.of("objects/info/alternates", "objects/info/http-alternates");

    /** Roots as configured (normalized) and as resolved on disk; a location may be written either way. */
    private final List<Path> configuredRoots;

    private final List<Path> realRoots;

    /**
     * @param allowedRoots absolute directories; may be empty, which rejects every local source
     * @throws IllegalStateException if a root is relative, missing or not a directory, so misconfiguration fails at
     *     startup rather than on the first request
     */
    LocalRepositories(List<Path> allowedRoots) {
        List<Path> configured = new ArrayList<>();
        List<Path> real = new ArrayList<>();
        for (Path root : allowedRoots) {
            if (!root.isAbsolute()) {
                throw new IllegalStateException("devmind.ingestion.allowed-roots must be absolute: " + root);
            }
            try {
                Path resolved = root.toRealPath();
                if (!Files.isDirectory(resolved)) {
                    throw new IllegalStateException("devmind.ingestion.allowed-roots entry isn't a directory: " + root);
                }
                configured.add(root.normalize());
                real.add(resolved);
            } catch (IOException ex) {
                throw new IllegalStateException("devmind.ingestion.allowed-roots entry doesn't exist: " + root, ex);
            }
        }
        this.configuredRoots = List.copyOf(configured);
        this.realRoots = List.copyOf(real);
    }

    /**
     * Opens the repository at {@code location}: a working tree with a {@code .git} directory or gitfile, or a bare
     * repository. The caller closes it.
     *
     * @throws SourceRejection {@code LOCATION_NOT_ALLOWED}, {@code LOCATION_NOT_FOUND}, {@code NOT_A_REPOSITORY}, or
     *     {@code UNREACHABLE} on I/O errors
     */
    Repository open(String location) throws SourceRejection {
        Path directory = resolveInsideRoots(location);
        Repository repository = build(directory);
        try {
            requireConfinedGitDirectory(repository);
            return repository;
        } catch (SourceRejection ex) {
            repository.close();
            throw ex;
        }
    }

    private Path resolveInsideRoots(String location) throws SourceRejection {
        Path requested;
        try {
            requested = Path.of(location);
        } catch (InvalidPathException ex) {
            throw new SourceRejection(SourceProblem.LOCATION_NOT_ALLOWED);
        }
        // Lexical check first, before touching the filesystem, so a rejection can't reveal whether a path outside the
        // roots exists.
        Path normalized = requested.normalize();
        boolean lexicallyInside = startsWithAny(normalized, configuredRoots) || isInsideRealRoot(normalized);
        if (!requested.isAbsolute() || !lexicallyInside) {
            throw new SourceRejection(SourceProblem.LOCATION_NOT_ALLOWED);
        }
        Path real = realPath(normalized);
        if (!isInsideRealRoot(real)) {
            throw new SourceRejection(SourceProblem.LOCATION_NOT_ALLOWED); // a symlink on the way leads out
        }
        if (!Files.isDirectory(real)) {
            throw new SourceRejection(SourceProblem.NOT_A_REPOSITORY);
        }
        return real;
    }

    private static Repository build(Path directory) throws SourceRejection {
        FileRepositoryBuilder builder =
                new FileRepositoryBuilder().setFS(FS.DETECTED).setMustExist(true);
        // No readEnvironment(): GIT_DIR and friends must not redirect the read.
        if (Files.exists(directory.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            builder.setWorkTree(directory.toFile()); // resolves a .git directory or gitfile
        } else {
            builder.setGitDir(directory.toFile()); // a bare repository, or not a repository at all
        }
        try {
            return builder.build();
        } catch (RepositoryNotFoundException | IllegalArgumentException ex) {
            throw new SourceRejection(SourceProblem.NOT_A_REPOSITORY);
        } catch (IOException ex) {
            throw new SourceRejection(SourceProblem.UNREACHABLE, ex);
        }
    }

    private void requireConfinedGitDirectory(Repository repository) throws SourceRejection {
        for (Path gitDirectory : List.of(
                repository.getDirectory().toPath(),
                repository.getCommonDirectory().toPath())) {
            if (!isInsideRealRoot(realPath(gitDirectory))) {
                throw new SourceRejection(SourceProblem.LOCATION_NOT_ALLOWED); // gitfile or commondir leads out
            }
            requireNoSymlinks(gitDirectory);
        }
        Path commonDirectory = repository.getCommonDirectory().toPath();
        for (String alternates : ALTERNATES) {
            if (Files.exists(commonDirectory.resolve(alternates), LinkOption.NOFOLLOW_LINKS)) {
                throw new SourceRejection(SourceProblem.LOCATION_NOT_ALLOWED); // objects borrowed from elsewhere
            }
        }
    }

    /** Any symlink inside the Git directory (a pack, a ref, {@code objects} itself) could point out of the root. */
    private static void requireNoSymlinks(Path gitDirectory) throws SourceRejection {
        LinkFinder finder = new LinkFinder();
        try {
            Files.walkFileTree(gitDirectory, finder); // doesn't follow links, so it sees them
        } catch (IOException ex) {
            throw new SourceRejection(SourceProblem.UNREACHABLE, ex);
        }
        if (finder.found) {
            throw new SourceRejection(SourceProblem.LOCATION_NOT_ALLOWED);
        }
    }

    /** Stops at the first symlink, or other non-regular entry such as a Windows junction. */
    private static final class LinkFinder extends SimpleFileVisitor<Path> {

        private boolean found;

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return visit(attrs);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            return visit(attrs);
        }

        private FileVisitResult visit(BasicFileAttributes attrs) {
            found = attrs.isSymbolicLink() || attrs.isOther();
            return found ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }
    }

    private static Path realPath(Path path) throws SourceRejection {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException ex) {
            throw new SourceRejection(SourceProblem.LOCATION_NOT_FOUND);
        } catch (IOException ex) {
            throw new SourceRejection(SourceProblem.UNREACHABLE, ex);
        }
    }

    private boolean isInsideRealRoot(Path path) {
        return startsWithAny(path, realRoots);
    }

    private static boolean startsWithAny(Path path, List<Path> roots) {
        return roots.stream().anyMatch(path::startsWith);
    }
}
