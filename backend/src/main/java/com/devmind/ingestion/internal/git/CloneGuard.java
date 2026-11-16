package com.devmind.ingestion.internal.git;

import com.devmind.ingestion.SourceProblem;
import com.devmind.ingestion.internal.domain.SourceRejection;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.jspecify.annotations.Nullable;

/**
 * Enforces a clone's wall-clock and disk limits while it runs (SEC-3, ERR-6). JGit polls {@link #isCancelled()}
 * throughout a fetch, so a clone that runs too long or grows too large is cancelled mid-transfer, not after the disk
 * has filled. JGit's own timeout only bounds each network read.
 *
 * <p>Deadlines use {@link System#nanoTime()}, a monotonic timer, rather than the application {@code Clock}: this
 * measures elapsed time, not a point in time to store (JAVA-9). Used by the one thread that runs the fetch.
 */
final class CloneGuard implements ProgressMonitor {

    /** Measuring the clone walks its directory, so it's done at most this often. */
    private static final long SIZE_CHECK_INTERVAL_NANOS = Duration.ofMillis(500).toNanos();

    private final Path directory;
    private final long maxBytes;
    private final long deadline;
    private long nextSizeCheck;
    private @Nullable SourceProblem tripped;

    CloneGuard(Path directory, long maxBytes, Duration timeout) {
        this.directory = directory;
        this.maxBytes = maxBytes;
        long now = System.nanoTime();
        this.deadline = now + timeout.toNanos();
        this.nextSizeCheck = now;
    }

    @Override
    public boolean isCancelled() {
        if (tripped != null) {
            return true;
        }
        long now = System.nanoTime();
        if (now - deadline >= 0) {
            tripped = SourceProblem.TIMED_OUT;
        } else if (now - nextSizeCheck >= 0) {
            nextSizeCheck = now + SIZE_CHECK_INTERVAL_NANOS;
            if (sizeOf(directory) > maxBytes) {
                tripped = SourceProblem.TOO_LARGE;
            }
        }
        return tripped != null;
    }

    /** Returns the limit that cancelled the clone, if one did. */
    Optional<SourceProblem> tripped() {
        return Optional.ofNullable(tripped);
    }

    /**
     * Checks both limits exactly once the clone has finished, since polling may have missed the last stretch.
     *
     * @throws SourceRejection {@code TIMED_OUT} or {@code TOO_LARGE}
     */
    void verifyFinished() throws SourceRejection {
        if (System.nanoTime() - deadline >= 0) {
            throw new SourceRejection(SourceProblem.TIMED_OUT);
        }
        if (sizeOf(directory) > maxBytes) {
            throw new SourceRejection(SourceProblem.TOO_LARGE);
        }
    }

    /** Bytes of regular files under {@code root}. Files that vanish mid-walk (JGit renames temp packs) are skipped. */
    static long sizeOf(Path root) {
        SizeCounter counter = new SizeCounter();
        try {
            Files.walkFileTree(root, counter);
        } catch (IOException ex) {
            return Long.MAX_VALUE; // can't measure it, so it can't be shown to be within the limit
        }
        return counter.total;
    }

    private static final class SizeCounter extends SimpleFileVisitor<Path> {

        private long total;

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()) {
                total += attrs.size();
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException ex) {
            return FileVisitResult.CONTINUE;
        }
    }

    @Override
    public void start(int totalTasks) {
        // progress isn't reported; only cancellation matters
    }

    @Override
    public void beginTask(String title, int totalWork) {
        // progress isn't reported; only cancellation matters
    }

    @Override
    public void update(int completed) {
        // progress isn't reported; only cancellation matters
    }

    @Override
    public void endTask() {
        // progress isn't reported; only cancellation matters
    }

    @Override
    public void showDuration(boolean enabled) {
        // progress isn't reported; only cancellation matters
    }
}
