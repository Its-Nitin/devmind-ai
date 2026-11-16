package com.devmind.ingestion;

/**
 * Turns committed files into indexable text: filters out what must not or need not be indexed, decodes, normalizes,
 * redacts secrets and hashes. Stateless and thread-safe; never throws for any file content.
 *
 * <p>Steps, in order: path deny-list, secret-file patterns, binary sniff, extension allow-list, UTF-8 decode (ISO-8859-1
 * fallback), BOM and line-ending normalization, secret redaction, SHA-256. The size cap ran earlier, in the snapshot
 * ({@link SnapshotStats#oversizedSkipped()}).
 */
public interface IngestionPipeline {

    /**
     * Returns the version of these rules, e.g. {@code filters-1}. It changes whenever a rule changes what is kept or
     * how content is normalized or redacted, so it belongs in the index config hash.
     */
    String rulesVersion();

    /** Runs {@code file} through every step; returns the indexable file or the first rule that excluded it. */
    FileOutcome process(SourceFile file);
}
