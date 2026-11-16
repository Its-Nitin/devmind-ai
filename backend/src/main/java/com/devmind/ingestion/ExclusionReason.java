package com.devmind.ingestion;

/**
 * Why a tracked entry isn't indexed. Every entry of a commit is either kept or counted under exactly one reason
 * (ERR-4), so {@code kept + excluded = total} holds in {@link IngestionStats}.
 */
public enum ExclusionReason {
    /** Under a build-output, dependency, IDE or generated directory, e.g. {@code target/} or {@code node_modules/}. */
    PATH("path"),
    /** A file that typically holds secrets, e.g. {@code .env}, {@code *.pem}, {@code id_rsa} (SEC-5). */
    SECRET_FILE("secretFile"),
    /** Larger than the per-file limit; skipped by the snapshot without being read. */
    SIZE("size"),
    /** Contains a NUL byte in its first 8 KB. */
    BINARY("binary"),
    /** Its extension isn't on the allow-list. */
    EXTENSION("extension"),
    /** A symlink entry; never followed (SEC-2). */
    SYMLINK("symlink"),
    /** A submodule entry; never fetched (SEC-3). */
    SUBMODULE("submodule"),
    /** A path too long to store, or malformed by a crafted repository. */
    INVALID_PATH("invalidPath");

    private final String key;

    ExclusionReason(String key) {
        this.key = key;
    }

    /** Returns the stable camelCase key used in revision stats, e.g. {@code secretFile}. */
    public String key() {
        return key;
    }
}
