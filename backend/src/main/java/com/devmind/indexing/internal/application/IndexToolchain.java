package com.devmind.indexing.internal.application;

import com.devmind.indexing.CodeParser;
import com.devmind.indexing.internal.domain.IndexConfigHash;
import com.devmind.indexing.internal.domain.SymbolChunker;
import com.devmind.ingestion.IngestionPipeline;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The versioned pieces that turn files into an index, and the config hash they add up to. One place, so the hash
 * the submit path keys jobs by and the hash the run records always agree.
 */
@Component
class IndexToolchain {

    /** {@code file_blob.parser_version} of blobs no parser handles (stored as {@code SKIPPED}). */
    static final String NO_PARSER = "none";

    private final List<CodeParser> parsers;
    private final IngestionPipeline pipeline;
    private final ActiveEmbeddingModel embedding;

    IndexToolchain(List<CodeParser> parsers, IngestionPipeline pipeline, ActiveEmbeddingModel embedding) {
        this.parsers = List.copyOf(parsers);
        this.pipeline = pipeline;
        this.embedding = embedding;
    }

    IngestionPipeline pipeline() {
        return pipeline;
    }

    /** Returns the parser for {@code language}, if one supports it. */
    Optional<CodeParser> parserFor(String language) {
        return parsers.stream().filter(parser -> parser.supports(language)).findFirst();
    }

    /** The embedding model, resolved on first use; see {@link ActiveEmbeddingModel#model()} for what it throws. */
    ActiveEmbeddingModel embedding() {
        return embedding;
    }

    /**
     * {@code index_config_hash} under the active embedding model: 64 lowercase hex characters.
     *
     * @throws com.devmind.ai.EmbeddingUnavailableException if the model server can't tell which model it serves
     */
    String configHash() {
        return configHash(embedding.model());
    }

    /** {@code index_config_hash} under {@code model}. */
    String configHash(ActiveEmbeddingModel.Model model) {
        return IndexConfigHash.of(
                parsers.stream().map(CodeParser::version).toList(),
                SymbolChunker.VERSION,
                pipeline.rulesVersion(),
                model.key());
    }
}
