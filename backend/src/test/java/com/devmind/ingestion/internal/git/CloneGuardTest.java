package com.devmind.ingestion.internal.git;

import static com.devmind.ingestion.internal.git.SnapshotAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.ingestion.SourceProblem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloneGuardTest {

    @TempDir
    Path clone;

    @Test
    void measuresRegularFilesRecursively() throws IOException {
        // Given
        Files.writeString(clone.resolve("HEAD"), "12345");
        Files.writeString(Files.createDirectories(clone.resolve("objects/pack")).resolve("p.pack"), "1234567890");

        // When / Then
        assertThat(CloneGuard.sizeOf(clone)).isEqualTo(15);
    }

    @Test
    void letsACloneWithinBothLimitsContinue() throws Exception {
        // Given
        Files.writeString(clone.resolve("HEAD"), "12345");
        CloneGuard guard = new CloneGuard(clone, 5, Duration.ofMinutes(1));

        // When / Then
        assertThat(guard.isCancelled()).isFalse();
        assertThat(guard.tripped()).isEmpty();
        guard.verifyFinished();
    }

    @Test
    void cancelsACloneThatGrowsPastTheSizeLimit() throws IOException {
        // Given
        Files.writeString(clone.resolve("HEAD"), "123456");
        CloneGuard guard = new CloneGuard(clone, 5, Duration.ofMinutes(1));

        // When / Then
        assertThat(guard.isCancelled()).isTrue();
        assertThat(guard.tripped()).contains(SourceProblem.TOO_LARGE);
        assertRejected(guard::verifyFinished, SourceProblem.TOO_LARGE);
    }

    @Test
    void cancelsACloneThatRunsPastTheTimeout() {
        // Given: the deadline passes at once
        CloneGuard guard = new CloneGuard(clone, Long.MAX_VALUE, Duration.ofNanos(1));

        // When / Then
        assertThat(guard.isCancelled()).isTrue();
        assertThat(guard.tripped()).contains(SourceProblem.TIMED_OUT);
        assertRejected(guard::verifyFinished, SourceProblem.TIMED_OUT);
    }
}
