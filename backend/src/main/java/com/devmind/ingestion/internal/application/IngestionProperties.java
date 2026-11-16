package com.devmind.ingestion.internal.application;

import com.devmind.ingestion.internal.domain.SnapshotLimits;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Where sources may come from and how much of them may be read, {@code devmind.ingestion.*} (SPRING-1, SEC-2, SEC-3).
 * Defaults are in {@code application.yaml}. Only server config sets these; nothing a user sends can widen them.
 *
 * @param allowedRoots absolute directories that local sources must lie in, each checked to exist at startup; empty
 *     rejects every local source
 * @param allowedHosts exact host names remote sources may use, e.g. {@code github.com}; empty rejects every remote
 *     source
 * @param cloneDirectory where remote clones are made, one temporary directory each; not shared between instances
 * @param cloneTimeout wall-clock limit for one clone
 * @param maxCloneSize on-disk size one clone may reach
 * @param maxFiles tracked entries a commit may have
 * @param maxFileSize larger files are skipped and counted
 */
@Validated
@ConfigurationProperties("devmind.ingestion")
record IngestionProperties(
        @DefaultValue List<Path> allowedRoots,
        @DefaultValue List<String> allowedHosts,
        @NotNull Path cloneDirectory,
        @NotNull @DurationMin(seconds = 1) Duration cloneTimeout,
        @NotNull DataSize maxCloneSize,
        @Min(1) int maxFiles,
        @NotNull DataSize maxFileSize) {

    IngestionProperties {
        allowedRoots = List.copyOf(allowedRoots);
        allowedHosts = List.copyOf(allowedHosts);
    }

    @AssertTrue(message = "max-clone-size and max-file-size must be positive")
    boolean isSizesPositive() {
        // Null when a @NotNull above already failed; that violation is the one to report.
        return maxCloneSize == null || maxFileSize == null || (maxCloneSize.toBytes() > 0 && maxFileSize.toBytes() > 0);
    }

    SnapshotLimits limits() {
        return new SnapshotLimits(maxFiles, maxFileSize.toBytes(), maxCloneSize.toBytes(), cloneTimeout);
    }
}
