package com.devmind.indexing.internal.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Indexing tuning, {@code devmind.indexing.*} (SPRING-1). Defaults are in {@code application.yaml}.
 *
 * @param batchSize files per write batch, each in its own transaction (DB-8: at least 500 in production; tests use
 *     fewer to cross batch boundaries)
 * @param embeddingBatchSize chunks embedded, then written in one transaction, between two heartbeats. Smaller than
 *     DB-8's 500 on purpose: on CPU a chunk takes ~1.6 s, so 500 would outlast the job lease and lose more work to a
 *     crash
 */
@Validated
@ConfigurationProperties("devmind.indexing")
record IndexingProperties(
        @Min(1) @Max(5000) int batchSize, @Min(1) @Max(1000) int embeddingBatchSize) {}
