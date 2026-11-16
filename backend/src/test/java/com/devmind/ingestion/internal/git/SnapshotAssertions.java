package com.devmind.ingestion.internal.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devmind.ingestion.SourceFile;
import com.devmind.ingestion.SourceProblem;
import com.devmind.ingestion.SourceSnapshot;
import com.devmind.ingestion.internal.domain.SnapshotLimits;
import com.devmind.ingestion.internal.domain.SourceRejection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** Shared helpers for the Git adapter tests. */
final class SnapshotAssertions {

    /** Generous limits; tests that exercise a limit lower just that one. */
    static final SnapshotLimits LIMITS =
            new SnapshotLimits(1_000, 1024 * 1024, 50L * 1024 * 1024, Duration.ofMinutes(1));

    private SnapshotAssertions() {}

    /** Reads every file as UTF-8, in stream order. */
    static Map<String, String> contents(SourceSnapshot snapshot) {
        Map<String, String> files = new LinkedHashMap<>();
        snapshot.files().forEach(file -> files.put(file.path(), text(file)));
        return files;
    }

    static String text(SourceFile file) {
        return new String(file.content(), StandardCharsets.UTF_8);
    }

    static void assertRejected(ThrowingCallable call, SourceProblem expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        SourceRejection.class,
                        rejection -> assertThat(rejection.problem()).isEqualTo(expected));
    }
}
