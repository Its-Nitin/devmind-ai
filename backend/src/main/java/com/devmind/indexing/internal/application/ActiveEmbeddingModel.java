package com.devmind.indexing.internal.application;

import com.devmind.ai.EmbeddingModelRef;
import com.devmind.ai.EmbeddingPort;
import com.devmind.ai.EmbeddingUnavailableException;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The embedding model indexing writes vectors for, and its {@code embedding_model} row (M3 step 2). Resolved on first
 * use rather than at startup, so the app starts while the model server is down; a failed resolution is retried on the
 * next use, a successful one holds until restart (the port's model doesn't change at runtime either).
 */
@Component
class ActiveEmbeddingModel {

    /** {@code chunk_embedding.embedding}'s {@code vector(1024)} (V5); another size needs a new migration (DB-10). */
    static final int SCHEMA_DIMENSIONS = 1024;

    private static final Logger log = LoggerFactory.getLogger(ActiveEmbeddingModel.class);

    private final EmbeddingPort port;
    private final IndexWriter writer;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile @Nullable Model resolved;

    ActiveEmbeddingModel(EmbeddingPort port, IndexWriter writer) {
        this.port = port;
        this.writer = writer;
    }

    /**
     * A registered model.
     *
     * @param id its {@code embedding_model.id}
     */
    record Model(long id, EmbeddingModelRef ref) {

        /** {@code provider/name@digest}: its part of the index config hash, and its name in revision stats. */
        String key() {
            return ref.provider() + "/" + ref.name() + "@" + ref.digest();
        }
    }

    /**
     * Returns the model, registering it on first use.
     *
     * @throws EmbeddingUnavailableException if the model server can't tell which model it serves right now
     * @throws IllegalStateException if the model's vectors don't fit the schema
     */
    Model model() {
        Model model = resolved;
        if (model != null) {
            return model;
        }
        lock.lock();
        try {
            model = resolved;
            if (model == null) {
                model = register(port.model());
                resolved = model;
            }
            return model;
        } finally {
            lock.unlock();
        }
    }

    private Model register(EmbeddingModelRef ref) {
        if (ref.dimensions() != SCHEMA_DIMENSIONS) {
            throw new IllegalStateException("Embedding model " + ref.name() + " has " + ref.dimensions()
                    + " dimensions; the schema stores " + SCHEMA_DIMENSIONS + " (see ADR-0006)");
        }
        Model model = new Model(writer.registerEmbeddingModel(ref), ref);
        log.info("Indexing embeds with {} ({} dimensions)", model.key(), ref.dimensions());
        return model;
    }

    /**
     * Embeds document texts with the model, one vector per text in order. Call outside any transaction (SPRING-2).
     *
     * @throws EmbeddingUnavailableException if the model server fails
     */
    List<float[]> embed(List<String> texts) {
        return port.embedDocuments(texts);
    }
}
