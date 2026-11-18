package com.devmind.indexing.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.indexing.internal.domain.IndexConfigHash;
import com.devmind.ingestion.ProjectSource;
import com.devmind.project.SourceType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexRequestTest {

    @Test
    void roundTripsThroughAJobPayload() {
        // Given
        IndexRequest request = new IndexRequest("main", new ProjectSource(SourceType.GIT_PUBLIC, "https://x.test/a/b"));

        // When / Then
        assertThat(IndexRequest.fromPayload(request.toPayload())).contains(request);
    }

    @Test
    void rejectsIncompleteOrUnknownPayloads() {
        assertThat(IndexRequest.fromPayload(Map.of("ref", "main"))).isEmpty();
        assertThat(IndexRequest.fromPayload(Map.of("ref", "main", "sourceType", "FTP", "sourceLocation", "/x")))
                .isEmpty();
        assertThat(IndexRequest.fromPayload(Map.of("ref", 1, "sourceType", "LOCAL", "sourceLocation", "/x")))
                .isEmpty();
    }

    @Test
    void configHashChangesWithAnyVersionButNotWithParserOrder() {
        String hash = IndexConfigHash.of(List.of("java-1", "ts-1"), "chunk-1", "filters-1", "none");

        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(IndexConfigHash.of(List.of("ts-1", "java-1"), "chunk-1", "filters-1", "none"))
                .isEqualTo(hash);
        assertThat(IndexConfigHash.of(List.of("java-2", "ts-1"), "chunk-1", "filters-1", "none"))
                .isNotEqualTo(hash);
        assertThat(IndexConfigHash.of(List.of("java-1", "ts-1"), "chunk-2", "filters-1", "none"))
                .isNotEqualTo(hash);
        assertThat(IndexConfigHash.of(List.of("java-1", "ts-1"), "chunk-1", "filters-2", "none"))
                .isNotEqualTo(hash);
        assertThat(IndexConfigHash.of(List.of("java-1", "ts-1"), "chunk-1", "filters-1", "bge-m3"))
                .isNotEqualTo(hash);
    }
}
