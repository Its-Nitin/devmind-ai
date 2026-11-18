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
    private final String configHash;

    IndexToolchain(List<CodeParser> parsers, IngestionPipeline pipeline) {
        this.parsers = List.copyOf(parsers);
        this.pipeline = pipeline;
        this.configHash = IndexConfigHash.of(
                parsers.stream().map(CodeParser::version).toList(),
                SymbolChunker.VERSION,
                pipeline.rulesVersion(),
                IndexConfigHash.NO_EMBEDDINGS);
    }

    IngestionPipeline pipeline() {
        return pipeline;
    }

    /** Returns the parser for {@code language}, if one supports it. */
    Optional<CodeParser> parserFor(String language) {
        return parsers.stream().filter(parser -> parser.supports(language)).findFirst();
    }

    /** {@code index_config_hash}: 64 lowercase hex characters. */
    String configHash() {
        return configHash;
    }
}
