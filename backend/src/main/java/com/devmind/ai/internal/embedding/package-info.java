/**
 * Embedding adapters behind {@link com.devmind.ai.EmbeddingPort}. Ollama is called over its HTTP API with Spring's
 * {@code RestClient}; no HTTP or provider type leaves this package (ARCH-7, ARCH-8).
 */
@NullMarked
package com.devmind.ai.internal.embedding;

import org.jspecify.annotations.NullMarked;
