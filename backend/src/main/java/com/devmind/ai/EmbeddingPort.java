package com.devmind.ai;

import java.util.List;

/**
 * Turns text into vectors with one embedding model (ADR-0006). Documents and queries are embedded differently: each
 * method applies the model's own prefix, so callers pass plain text.
 *
 * <p>Implementations are thread-safe. Tests use a deterministic fake and never a real model (AI-11).
 */
public interface EmbeddingPort {

    /**
     * Returns the model behind this port, resolving it from the model server on first use.
     *
     * @return the model, never null
     * @throws EmbeddingUnavailableException if the model server can't be reached or doesn't have the model
     */
    EmbeddingModelRef model();

    /**
     * Embeds texts to be searched, such as chunks, in the order given. Sends them to the model in batches.
     *
     * @param texts the texts, possibly empty; an empty list makes no model call
     * @return one vector of {@link EmbeddingModelRef#dimensions()} floats per text, in input order
     * @throws EmbeddingUnavailableException if any batch fails; nothing is returned for the batches that succeeded
     */
    List<float[]> embedDocuments(List<String> texts);

    /**
     * Embeds a search query.
     *
     * @param text the query
     * @return a vector of {@link EmbeddingModelRef#dimensions()} floats
     * @throws EmbeddingUnavailableException if the model call fails
     */
    float[] embedQuery(String text);
}
