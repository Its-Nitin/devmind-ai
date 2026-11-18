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
 */
@Validated
@ConfigurationProperties("devmind.indexing")
record IndexingProperties(@Min(1) @Max(5000) int batchSize) {}
