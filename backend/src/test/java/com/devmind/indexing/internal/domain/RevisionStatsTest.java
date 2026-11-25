package com.devmind.indexing.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** {@code repository_revision.stats} written by earlier runs stays readable after the shape grows. */
class RevisionStatsTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void readsStatsWrittenBeforeTheEmbeddingPhaseExisted() {
        // Given: what an M2 run stored
        String m2 = """
                {"filesKept":5,"excluded":{"path":1},"blobsReused":0,"blobsNew":5,"symbols":6,"chunks":9,
                 "parseFailures":0,"redactions":1,"encodingFallbacks":0,"durationMs":120}""";

        // When
        RevisionStats stats = json.readValue(m2, RevisionStats.class);

        // Then
        assertThat(stats.filesKept()).isEqualTo(5);
        assertThat(stats.excluded()).isEqualTo(Map.of("path", 1));
        assertThat(stats.embeddings()).isNull();
    }

    @Test
    void roundTripsTheEmbeddingStats() {
        RevisionStats stats = new RevisionStats(
                5,
                Map.of("path", 1),
                0,
                5,
                6,
                9,
                0,
                1,
                0,
                120,
                new RevisionStats.EmbeddingStats("ollama/mxbai-embed-large@4688", 7, 2, 900));

        assertThat(json.readValue(json.writeValueAsString(stats), RevisionStats.class))
                .isEqualTo(stats);
    }
}
