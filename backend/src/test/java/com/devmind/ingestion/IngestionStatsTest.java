package com.devmind.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestionStatsTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void startsFromWhatTheSnapshotSkipped() {
        // When
        IngestionStats stats = IngestionStats.of(new SnapshotStats(5, 1, 2, 3, 4));

        // Then: the 5 files are still to be processed, so only the skipped entries count yet
        assertThat(stats.kept()).isZero();
        assertThat(stats.excluded(ExclusionReason.SYMLINK)).isEqualTo(1);
        assertThat(stats.excluded(ExclusionReason.SUBMODULE)).isEqualTo(2);
        assertThat(stats.excluded(ExclusionReason.SIZE)).isEqualTo(3);
        assertThat(stats.excluded(ExclusionReason.INVALID_PATH)).isEqualTo(4);
        assertThat(stats.total()).isEqualTo(10);
    }

    @Test
    void addsKeptFilesWithTheirRedactionsAndFallbacksAndExclusionsByReason() {
        // Given
        NormalizedFile redacted = new NormalizedFile("a.yml", "yaml", "x", HASH, 1, 1, 2, true);

        // When
        IngestionStats stats = IngestionStats.of(new SnapshotStats(0, 0, 0, 0, 0))
                .add(new FileOutcome.Kept(redacted))
                .add(new FileOutcome.Excluded(ExclusionReason.BINARY))
                .add(new FileOutcome.Excluded(ExclusionReason.BINARY));

        // Then
        assertThat(stats.kept()).isEqualTo(1);
        assertThat(stats.redactions()).isEqualTo(2);
        assertThat(stats.encodingFallbacks()).isEqualTo(1);
        assertThat(stats.excluded(ExclusionReason.BINARY)).isEqualTo(2);
        assertThat(stats.total()).isEqualTo(3);
    }

    @Test
    void listsEveryReasonInDeclarationOrder() {
        assertThat(new IngestionStats(0, Map.of(), 0, 0).excluded())
                .containsOnlyKeys(ExclusionReason.values())
                .allSatisfy((reason, count) -> assertThat(count).isZero());
        assertThat(new IngestionStats(0, Map.of(), 0, 0).excluded().keySet()).containsExactly(ExclusionReason.values());
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IngestionStats(0, Map.of(ExclusionReason.PATH, -1), 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new IngestionStats(-1, Map.of(), 0, 0));
    }

    @Test
    void normalizedFileNeverPrintsItsContent() {
        assertThat(new NormalizedFile("a.yml", "yaml", "password: hunter2hunter2", HASH, 1, 1, 0, false))
                .asString()
                .doesNotContain("hunter2")
                .contains("a.yml");
    }

    @Test
    void normalizedFileRejectsMalformedHashes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NormalizedFile("a.yml", "yaml", "", "A".repeat(64), 0, 0, 0, false));
    }
}
