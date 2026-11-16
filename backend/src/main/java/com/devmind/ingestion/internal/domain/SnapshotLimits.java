package com.devmind.ingestion.internal.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Resource bounds on reading one source (SEC-3, ERR-6, threat T3).
 *
 * @param maxFiles tracked entries a commit may have; more fails the snapshot
 * @param maxFileBytes larger files are skipped without loading their content
 * @param maxCloneBytes on-disk size a clone may reach; checked while it downloads and after
 * @param cloneTimeout wall-clock limit for cloning, also used as the network read timeout
 */
public record SnapshotLimits(int maxFiles, long maxFileBytes, long maxCloneBytes, Duration cloneTimeout) {

    /** @throws IllegalArgumentException if a limit isn't positive */
    public SnapshotLimits {
        Objects.requireNonNull(cloneTimeout, "cloneTimeout");
        if (maxFiles < 1 || maxFileBytes < 1 || maxCloneBytes < 1 || !cloneTimeout.isPositive()) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }
}
