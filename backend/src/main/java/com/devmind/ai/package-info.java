/**
 * AI module: the ports through which every model call goes (ADR-0006, AI-2), with provider adapters in {@code
 * internal}. Provider SDK and HTTP types never leave {@code internal} (ARCH-8).
 *
 * <p>Public API so far (M3): {@link com.devmind.ai.EmbeddingPort}, which turns text into vectors for one {@link
 * com.devmind.ai.EmbeddingModelRef}. The chat port and prompt registry arrive in M4.
 */
@NullMarked
package com.devmind.ai;

import org.jspecify.annotations.NullMarked;
