package com.devmind.ai;

import java.util.Objects;

/**
 * Identifies the exact model behind an {@link EmbeddingPort}. Vectors are comparable only within one model: a
 * different name or digest means re-embedding (ADR-0003, ADR-0006).
 *
 * @param provider the serving runtime, e.g. {@code ollama}
 * @param name the model name as configured, e.g. {@code mxbai-embed-large}
 * @param dimensions the length of every vector the model returns
 * @param digest the model server's content digest for the weights, so a re-pulled tag counts as a new model
 */
public record EmbeddingModelRef(String provider, String name, int dimensions, String digest) {

    /** @throws IllegalArgumentException if a text field is blank or {@code dimensions} isn't positive */
    public EmbeddingModelRef {
        requireText(provider, "provider");
        requireText(name, "name");
        requireText(digest, "digest");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive: " + dimensions);
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
